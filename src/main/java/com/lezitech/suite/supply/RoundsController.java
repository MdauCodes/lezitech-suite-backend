package com.lezitech.suite.supply;

import com.lezitech.suite.common.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Supply rounds for staff. Who may do what is set in RoleGuard; what depends on being this round's driver is checked
 * in RoundsService. This class is not itself transactional, so a wrong code is saved before the answer is refused.
 */
@RestController
@RequestMapping("/api/rounds")
public class RoundsController {

    private final RoundsService rounds;

    public RoundsController(RoundsService rounds) {
        this.rounds = rounds;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String date) {
        return rounds.list(date);
    }

    @GetMapping("/orders-ready")
    public List<Map<String, Object>> ordersReady() {
        return rounds.ordersReady();
    }

    @GetMapping("/mine")
    public List<Map<String, Object>> mine() {
        return rounds.mine();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return rounds.create(body);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        return rounds.get(id);
    }

    @PostMapping("/{id}/load")
    public Map<String, Object> load(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return rounds.saveLoad(id, body);
    }

    @PostMapping("/{id}/accept")
    public Map<String, Object> accept(@PathVariable UUID id) {
        return rounds.accept(id);
    }

    @PostMapping("/{id}/back")
    public Map<String, Object> back(@PathVariable UUID id) {
        return rounds.back(id);
    }

    @PostMapping("/stops/{stopId}/arrived")
    public Map<String, Object> arrived(@PathVariable UUID stopId, @RequestBody(required = false) Map<String, Object> body) {
        return rounds.arrived(stopId, body == null ? Map.of() : body);
    }

    @PostMapping("/stops/{stopId}/code")
    public Map<String, Object> code(@PathVariable UUID stopId, @RequestBody Map<String, Object> body) {
        RoundsService.CodeResult r = rounds.code(stopId, body);
        if (!r.ok()) {
            throw ApiException.bad(r.message());
        }
        return r.stop();
    }

    @PostMapping("/stops/{stopId}/cash")
    public Map<String, Object> cash(@PathVariable UUID stopId, @RequestBody Map<String, Object> body) {
        return rounds.cash(stopId, body);
    }
}
