package com.lezitech.suite.demo;

import com.lezitech.suite.common.Body;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** One-click demo logins for pilots: pick a sector and a role, get signed in. */
@RestController
@RequestMapping("/api/public/demo")
public class DemoController {

    private final DemoService demos;

    public DemoController(DemoService demos) {
        this.demos = demos;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("enabled", demos.enabled(), "workspaces", demos.enabled() ? demos.list() : List.of());
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        return demos.login(b.str("sector"), b.str("role").toUpperCase());
    }
}
