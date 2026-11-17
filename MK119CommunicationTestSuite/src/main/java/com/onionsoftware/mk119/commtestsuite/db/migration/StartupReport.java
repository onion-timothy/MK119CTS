package com.onionsoftware.mk119.commtestsuite.db.migration;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 기동 시 스키마 확인과 마이그레이션 결과. GET /api/system/startup 이 그대로 내보내고,
 * 문제가 있으면 모든 화면의 상단 바에 한 줄로 보인다(fragments/tabler.html 의 navbar).
 */
@Component
public class StartupReport {

    public enum Status {OK, SKIPPED, FAILED}

    /** 단계 하나의 결과 */
    public static final class Step {
        private final String name;
        private final Status status;
        private final int changed;
        private final String message;
        private final long millis;

        public Step(String name, Status status, int changed, String message, long millis) {
            this.name = name;
            this.status = status;
            this.changed = changed;
            this.message = message;
            this.millis = millis;
        }

        public String getName() { return name; }
        public Status getStatus() { return status; }
        /** 바꾼 행·열 수 */
        public int getChanged() { return changed; }
        public String getMessage() { return message; }
        public long getMillis() { return millis; }
    }

    private final List<Step> steps = new CopyOnWriteArrayList<>();
    private volatile List<String> schemaProblems = Collections.emptyList();
    private volatile String database = "";
    private volatile LocalDateTime finishedAt;

    void reset(String database) {
        steps.clear();
        schemaProblems = Collections.emptyList();
        this.database = database;
        finishedAt = null;
    }

    void add(Step step) { steps.add(step); }

    void setSchemaProblems(List<String> problems) { schemaProblems = Collections.unmodifiableList(new ArrayList<>(problems)); }

    void finish() { finishedAt = LocalDateTime.now(); }

    public String getDatabase() { return database; }
    public List<Step> getSteps() { return Collections.unmodifiableList(steps); }
    public List<String> getSchemaProblems() { return schemaProblems; }
    /** 아직 기동 중이면 null */
    public LocalDateTime getFinishedAt() { return finishedAt; }

    /**
     * 사람이 알아야 할 문제. 빠진 테이블·열과 실패한 단계. 화면에 그대로 나가므로 영어 문장만 담는다 —
     * 실패 원인은 DB 드라이버가 로캘 언어로 주기도 해서 여기 넣지 않고 steps[].message 에만 둔다.
     */
    public List<String> getProblems() {
        List<String> out = new ArrayList<>(schemaProblems);
        for (Step s : steps) {
            if (s.status == Status.FAILED) out.add("Startup step \"" + s.name + "\" failed and was rolled back.");
        }
        return out;
    }

    public boolean isOk() { return getProblems().isEmpty(); }
}
