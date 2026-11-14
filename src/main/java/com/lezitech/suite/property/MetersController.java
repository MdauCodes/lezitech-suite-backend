package com.lezitech.suite.property;

import com.lezitech.suite.common.Body;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Meter readings, for the caretaker on their phone (and the owner, managers and accounts). */
@RestController
@RequestMapping("/api/meters")
@Transactional
public class MetersController {

    private final Utilities utilities;

    public MetersController(Utilities utilities) {
        this.utilities = utilities;
    }

    @GetMapping
    public List<Map<String, Object>> board() {
        return utilities.board();
    }

    @GetMapping("/units/{spaceId}")
    public List<Map<String, Object>> history(@PathVariable UUID spaceId) {
        return utilities.history(spaceId);
    }

    /** {spaceId, kind WATER|POWER, reading, readOn, note, newMeter} */
    @PostMapping("/readings")
    public Map<String, Object> record(@RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        return utilities.record(UUID.fromString(b.str("spaceId")), b.str("kind", ""), b.num("reading", null),
                b.has("readOn") ? b.date("readOn") : null, b.str("note", null), b.bool("newMeter", false));
    }

    @PostMapping("/readings/{id}/photo")
    public Map<String, Object> photo(@PathVariable UUID id, @RequestParam("file") MultipartFile upload) throws IOException {
        return utilities.addPhoto(id, upload.getBytes());
    }

    @PostMapping("/readings/{id}/delete")
    public Map<String, Object> remove(@PathVariable UUID id) {
        utilities.remove(id);
        return Map.of("removed", true);
    }
}
