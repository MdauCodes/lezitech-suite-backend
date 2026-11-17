package com.lezitech.suite.finance;

import com.lezitech.suite.common.Body;
import com.lezitech.suite.files.FileService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Expenses: owners, managers and accountants see and keep them; anyone with an open till can pay something from it. */
@RestController
@RequestMapping("/api/expenses")
@Transactional
public class ExpenseController {

    private final ExpenseService expenses;
    private final FileService files;

    public ExpenseController(ExpenseService expenses, FileService files) {
        this.expenses = expenses;
        this.files = files;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                    @RequestParam(required = false) String category) {
        TenantContext.requireRole("MANAGER", "ACCOUNTANT");
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Nairobi"));
        return expenses.list(from == null ? today.withDayOfMonth(1) : from, to == null ? today : to, category);
    }

    /** The categories to choose from (anyone: the till's "Pay from the till" uses them too). */
    @GetMapping("/categories")
    public List<String> categories() {
        return expenses.categories();
    }

    @PostMapping
    public Map<String, Object> add(@RequestBody Map<String, Object> raw) {
        return expenses.add(new Body(raw));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return expenses.update(id, new Body(raw));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable UUID id) {
        expenses.delete(id);
        return Map.of("ok", true);
    }

    /** A photo or PDF of the receipt. Whoever recorded it, or the back office, can attach it. */
    @PostMapping("/{id}/receipt")
    public Map<String, Object> receipt(@PathVariable UUID id, @RequestParam("file") MultipartFile upload) throws IOException {
        Map<String, Object> e = expenses.one(id);
        TenantContext.Caller me = TenantContext.caller();
        if (!ExpenseService.BACK_OFFICE.contains(me.role()) && !String.valueOf(me.userId()).equals(String.valueOf(e.get("createdBy")))) {
            TenantContext.requireRole("MANAGER", "ACCOUNTANT");
        }
        UUID file = files.save(FileService.Purpose.EXPENSE_RECEIPT, upload.getBytes());
        expenses.setReceipt(id, file);
        return Map.of("ok", true, "receiptFileId", file);
    }
}
