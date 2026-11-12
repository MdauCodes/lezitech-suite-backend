package com.lezitech.suite.auth;

import com.lezitech.suite.common.Body;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Settings → "Let LeziTech help": the owner grants LeziTech 5 hours of access, and can stop it any time. */
@RestController
@RequestMapping("/api/support-access")
@Transactional
public class SupportController {

    private final SupportAccess support;

    public SupportController(SupportAccess support) {
        this.support = support;
    }

    @GetMapping
    public Map<String, Object> status() {
        return support.status();
    }

    @PostMapping
    public Map<String, Object> grant(@RequestBody(required = false) Map<String, Object> raw) {
        return support.grant(new Body(raw).str("note", null));
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        return support.stop();
    }
}
