"""S17 — 그룹 트리 (설명서 III장).

장비는 그룹 계층으로 관리된다. 그룹을 만들고 이름을 바꾸고 지우는 흐름과,
그룹에 속한 장비가 트리에 따라 붙는지를 본다.
"""
import time
import cts

NAME = '그룹 트리'


def _tree():
    return cts.ok('/group/groups/treeData')


def _find(nodes, text):
    """treeData 에서 이름이 text 인 노드를 찾는다. 평면 목록과 중첩 모두 다룬다."""
    for n in nodes or []:
        if not isinstance(n, dict):
            continue
        if n.get('text') == text:
            return n
        hit = _find(n.get('children'), text)
        if hit:
            return hit
    return None


def run(ctx):
    name = cts.PREFIX + 's17grp'
    renamed = cts.PREFIX + 's17grp_renamed'
    gid = None
    dev_id = None
    try:
        ctx.given('ROOT(id=1) 아래에 그룹 ZZTMP_s17grp 를 만든다 '
                  '(PUT /api/group/addGroup {name, parentId: 1})')
        before = len(_tree() or [])
        created = cts.ok('/group/addGroup', 'PUT',
                         {'name': name, 'parentId': 1})
        gid = created.get('id') if isinstance(created, dict) else None
        ctx.check(gid is not None, '생성되고 id 가 돌아온다', created)
        time.sleep(1)
        ctx.check(_find(_tree(), name) is not None,
                  '장비 화면이 그리는 트리 데이터(GET /api/group/groups/treeData)에 나타난다',
                  name)

        ctx.given('그 그룹을 group.id 로 지정해 장비 ZZTMP_s17dev 를 등록한다')
        dev = cts.add_device(cts.PREFIX + 's17dev', cts.PORT_BASE + 95, group_id=gid)
        dev_id = dev['id']
        time.sleep(2)
        in_group = cts.ok('/device/devices/%d' % gid)
        ctx.check(any(d['id'] == dev_id for d in in_group),
                  '그룹으로 조회하면(GET /api/device/devices/{groupId}) 그 장비가 나온다',
                  [d['name'] for d in in_group])
        ctx.check(cts.ok('/device/%d' % dev_id)['group']['id'] == gid,
                  '장비를 직접 조회해도 group.id 가 그 그룹을 가리킨다',
                  cts.ok('/device/%d' % dev_id)['group'])

        # 화면이 보내는 것과 똑같이 {id, name} 만 보낸다. 받은 객체를 그대로 저장하면
        # parentId 가 null 로 덮여 트리가 끊기므로, 계층이 살아 있는지도 함께 본다.
        ctx.given('그룹 이름을 바꾼다. 화면이 보내는 것과 똑같이 {id, name} 만 담아 '
                  'POST /api/group/renameGroup 을 호출한다. 받은 객체를 그대로 저장하면 '
                  'parentId 가 null 로 덮여 트리 계층이 끊기므로 소속까지 함께 본다')
        cts.ok('/group/renameGroup', 'POST', {'id': gid, 'name': renamed})
        time.sleep(1)
        node = _find(_tree(), renamed)
        ctx.check(node is not None, '새 이름으로 트리에 나타난다', renamed)
        ctx.check(_find(_tree(), name) is None,
                  '옛 이름은 트리에서 사라진다 (복제가 아니라 이름 변경이다)', name)
        ctx.check(node is not None and node.get('parent') == 'g_1',
                  '상위 그룹이 ROOT(g_1) 그대로다. parentId 가 날아가지 않았다 — '
                  '날아가면 treeData 가 NPE 500 을 내 장비 화면 트리가 아예 뜨지 않는다',
                  node.get('parent') if node else '(없음)')
        ctx.check(cts.ok('/device/%d' % dev_id)['group']['id'] == gid,
                  '그 그룹에 속한 장비의 소속도 그대로다',
                  cts.ok('/device/%d' % dev_id)['group'])
    finally:
        if dev_id:
            cts.delete_device_tree(dev_id)
        if gid:
            cts.api('/group/deleteGroup/%d' % gid, 'DELETE')
            time.sleep(1)
            ctx.given('장비를 먼저 지운 뒤 그룹을 삭제한다 '
                      '(DELETE /api/group/deleteGroup/{id})')
            ctx.check(_find(_tree(), renamed) is None,
                      '트리에서 사라진다. 삭제 후 treeData 조회도 정상이다',
                      renamed)
