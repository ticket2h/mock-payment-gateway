package com.ticket2h.mockpay.chaos;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/chaos")
class ChaosController {

    private final ChaosService chaosService;

    ChaosController(ChaosService chaosService) {
        this.chaosService = chaosService;
    }

    @GetMapping
    ChaosConfig get() {
        return chaosService.current();
    }

    /** Body có thể chỉ chứa một phần các trường; trường thiếu nhận giá trị mặc định (không gộp với cấu hình cũ). */
    @PutMapping
    ChaosConfig put(@RequestBody(required = false) @Valid ChaosUpdateRequest request) {
        ChaosConfig config = request == null ? ChaosConfig.DEFAULT : request.toConfig();
        return chaosService.update(config);
    }

    @DeleteMapping
    ChaosConfig reset() {
        return chaosService.reset();
    }
}
