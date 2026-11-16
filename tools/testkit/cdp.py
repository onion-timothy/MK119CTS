"""헤드리스 Chrome 을 CDP 로 조작하는 최소 드라이버.

puppeteer/playwright 를 쓸 수 없는 환경이라(노드 깨짐, pip 차단) WebSocket 을 직접 구현한다.
필요한 건 텍스트 프레임 송수신뿐이라 RFC6455 의 일부만 다룬다.
"""
import base64
import json
import os
import shutil
import socket
import struct
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request

CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'


def _free_port():
    """비어 있는 포트를 하나 잡는다.

    디버깅 포트를 고정하면 두 번째 인스턴스가 포트를 못 잡고 첫 번째 브라우저에
    붙어 버린다. 그러면 창 두 개가 필요한 시험(편집 세션 같은)이 조용히 틀린다.
    """
    s = socket.socket()
    try:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]
    finally:
        s.close()


class WS:
    def __init__(self, url):
        _, rest = url.split('://', 1)
        hostport, path = rest.split('/', 1)
        host, port = hostport.split(':')
        self.sock = socket.create_connection((host, int(port)))
        self.sock.settimeout(30)
        key = base64.b64encode(os.urandom(16)).decode()
        req = ('GET /%s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\n'
               'Connection: Upgrade\r\nSec-WebSocket-Key: %s\r\n'
               'Sec-WebSocket-Version: 13\r\n\r\n' % (path, hostport, key))
        self.sock.sendall(req.encode())
        self.buf = b''
        while b'\r\n\r\n' not in self.buf:
            self.buf += self.sock.recv(4096)
        head, self.buf = self.buf.split(b'\r\n\r\n', 1)
        if b'101' not in head.split(b'\r\n')[0]:
            raise RuntimeError('웹소켓 업그레이드 실패: %s' % head[:200])

    def _recv(self, n):
        while len(self.buf) < n:
            d = self.sock.recv(65536)
            if not d:
                raise RuntimeError('연결이 끊김')
            self.buf += d
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def send(self, text):
        payload = text.encode()
        mask = os.urandom(4)
        n = len(payload)
        if n < 126:
            head = struct.pack('!BB', 0x81, 0x80 | n)
        elif n < 65536:
            head = struct.pack('!BBH', 0x81, 0x80 | 126, n)
        else:
            head = struct.pack('!BBQ', 0x81, 0x80 | 127, n)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(head + mask + masked)

    def recv(self):
        while True:
            b0, b1 = self._recv(2)
            n = b1 & 0x7F
            if n == 126:
                n = struct.unpack('!H', self._recv(2))[0]
            elif n == 127:
                n = struct.unpack('!Q', self._recv(8))[0]
            data = self._recv(n)
            op = b0 & 0x0F
            if op == 1:
                return data.decode()
            if op == 8:
                raise RuntimeError('연결 종료 프레임')
            # ping/pong/binary 는 무시


class Chrome:
    def __init__(self, width=1500, height=1000, scale=2):
        # 인스턴스마다 포트와 프로필을 따로 준다. 공유하면 두 번째가 첫 번째에 붙는다.
        self.port = _free_port()
        self.profile = tempfile.mkdtemp(prefix='cdpprof-')
        self.proc = subprocess.Popen(
            [CHROME, '--headless=new', '--remote-debugging-port=%d' % self.port,
             '--user-data-dir=%s' % self.profile,
             '--no-first-run', '--no-default-browser-check',
             '--hide-scrollbars', '--force-device-scale-factor=%d' % scale,
             '--window-size=%d,%d' % (width, height), 'about:blank'],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(60):
            try:
                urllib.request.urlopen('http://127.0.0.1:%d/json/version' % self.port, timeout=1)
                break
            except Exception:
                time.sleep(0.5)
        else:
            raise RuntimeError('Chrome 이 디버깅 포트를 열지 않음')
        tabs = json.load(urllib.request.urlopen('http://127.0.0.1:%d/json' % self.port))
        page = next(t for t in tabs if t['type'] == 'page')
        self.ws = WS(page['webSocketDebuggerUrl'])
        self.id = 0
        self.call('Page.enable')
        self.call('Runtime.enable')
        self.call('Emulation.setDeviceMetricsOverride',
                  width=width, height=height, deviceScaleFactor=scale, mobile=False)

    def call(self, method, **params):
        self.id += 1
        mid = self.id
        self.ws.send(json.dumps({'id': mid, 'method': method, 'params': params}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get('id') == mid:
                if 'error' in msg:
                    raise RuntimeError('%s: %s' % (method, msg['error']))
                return msg.get('result', {})

    def goto(self, url, wait=2.5):
        self.call('Page.navigate', url=url)
        time.sleep(wait)

    def js(self, expr, wait=0.6):
        r = self.call('Runtime.evaluate', expression=expr,
                      returnByValue=True, awaitPromise=True)
        time.sleep(wait)
        res = r.get('result', {})
        if r.get('exceptionDetails'):
            raise RuntimeError('JS 오류: %s' % r['exceptionDetails'].get('text'))
        return res.get('value')

    def shot(self, path, full=False, fit=None, pad=24):
        """fit 에 선택자를 주면 그 요소 아래까지만 잘라 찍는다.

        화면이 세로로 길게 잡혀 있어 짧은 폼은 아래쪽이 텅 빈 채로 찍힌다.
        문서에 넣으면 그만큼 작아 보이므로 내용 높이에 맞춘다.
        """
        kw = {'format': 'png'}
        if full:
            kw['captureBeyondViewport'] = True
        if fit:
            h = self.js("(function(){var e=document.querySelector('%s');"
                        "return e ? Math.ceil(e.getBoundingClientRect().bottom) : 0;})()"
                        % fit, wait=0)
            if h:
                m = self.call('Page.getLayoutMetrics')
                w = m['cssLayoutViewport']['clientWidth']
                kw['clip'] = {'x': 0, 'y': 0, 'width': w,
                              'height': min(h + pad, m['cssContentSize']['height']),
                              'scale': 1}
        r = self.call('Page.captureScreenshot', **kw)
        open(path, 'wb').write(base64.b64decode(r['data']))
        return path

    def open_tab(self, url):
        """같은 브라우저에 새 탭을 열고 그 탭을 다루는 핸들을 돌려준다.

        창 두 개가 필요한 시험(편집 세션)에 쓴다. 브라우저를 따로 띄우면
        WebSocket 이 붙지 않는 경우가 있어, 탭으로 나누는 편이 실제 사용에 가깝다.
        """
        target_id = self.call('Target.createTarget', url=url)['targetId']
        tab = Chrome.__new__(Chrome)
        tab.proc = None
        tab.profile = None
        tab.port = self.port
        tab.ws = WS('ws://127.0.0.1:%d/devtools/page/%s' % (self.port, target_id))
        tab.id = 0
        tab.call('Page.enable')
        tab.call('Runtime.enable')
        return tab

    def close(self):
        if self.proc is None:          # open_tab 으로 만든 핸들은 브라우저를 죽이지 않는다
            return
        try:
            self.proc.terminate()
            self.proc.wait(10)
        except Exception:
            self.proc.kill()
        shutil.rmtree(self.profile, ignore_errors=True)
