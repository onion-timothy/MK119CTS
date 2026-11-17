package com.onionsoftware.mk119.commtestsuite.web;

import com.onionsoftware.mk119.commtestsuite.db.migration.StartupReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 서버 상태 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final StartupReport startupReport;

    public SystemController(StartupReport startupReport) {
        this.startupReport = startupReport;
    }

    /**
     * 기동 시 스키마 확인·마이그레이션 결과.
     * {database, finishedAt, ok, problems[], schemaProblems[], steps[{name, status, changed, message, millis}]}
     */
    @GetMapping("/startup")
    public StartupReport startup() {
        return startupReport;
    }
}
