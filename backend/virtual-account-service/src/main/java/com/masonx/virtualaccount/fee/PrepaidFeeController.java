package com.masonx.virtualaccount.fee;

import com.masonx.common.tenant.Mode;
import com.masonx.feeengine.FeeAssessment;
import com.masonx.virtualaccount.domain.po.PrepaidFeeAssessmentSnapshot;
import com.masonx.virtualaccount.domain.po.PrepaidFeeSchedule;
import com.masonx.virtualaccount.domain.po.PrepaidFeeScheduleVersion;
import com.masonx.virtualaccount.vcc.dto.PagedResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class PrepaidFeeController {

    private final PrepaidFeeAssessmentService service;

    public PrepaidFeeController(PrepaidFeeAssessmentService service) {
        this.service = service;
    }

    @GetMapping("/v1/prepaid-fees/schedules")
    public ResponseEntity<PagedResult<PrepaidFeeSchedule>> listSchedules(
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "TEST") Mode mode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.listSchedules(merchantId, mode, page, size));
    }

    @PostMapping("/v1/prepaid-fees/schedules")
    public ResponseEntity<PrepaidFeeSchedule> createSchedule(
            @Valid @RequestBody CreatePrepaidFeeScheduleCommand command) {
        return ResponseEntity.ok(service.createSchedule(command));
    }

    @GetMapping("/v1/prepaid-fees/schedules/{scheduleId}/versions")
    public ResponseEntity<List<PrepaidFeeScheduleVersion>> listVersions(
            @PathVariable String scheduleId,
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "TEST") Mode mode) {
        return ResponseEntity.ok(service.listVersions(scheduleId, merchantId, mode));
    }

    @PostMapping("/v1/prepaid-fees/schedules/{scheduleId}/versions")
    public ResponseEntity<PrepaidFeeScheduleVersion> publishVersion(
            @PathVariable String scheduleId,
            @Valid @RequestBody PublishPrepaidFeeScheduleVersionCommand command) {
        PublishPrepaidFeeScheduleVersionCommand scoped = new PublishPrepaidFeeScheduleVersionCommand(
                scheduleId,
                command.merchantId(),
                command.mode(),
                command.version(),
                command.status(),
                command.feeCurrency(),
                command.feeScale(),
                command.roundingMode(),
                command.effectiveFrom(),
                command.effectiveTo(),
                command.rules(),
                command.metadata());
        return ResponseEntity.ok(service.publishVersion(scoped));
    }

    @PostMapping("/v1/prepaid-fees/preview")
    public ResponseEntity<FeeAssessment> preview(@Valid @RequestBody PreviewPrepaidFeeCommand command) {
        return ResponseEntity.ok(service.preview(command));
    }

    @GetMapping("/v1/prepaid-fees/assessments")
    public ResponseEntity<PagedResult<PrepaidFeeAssessmentSnapshot>> listAssessments(
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "TEST") Mode mode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.listAssessments(merchantId, mode, page, size));
    }
}
