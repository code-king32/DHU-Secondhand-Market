package com.dhu.secondhand.community;

import com.dhu.secondhand.common.SessionAuth;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api")
public class CommunityController {

    private final CommunityService service;
    private final SessionAuth auth;

    public CommunityController(CommunityService service, SessionAuth auth) {
        this.service = service;
        this.auth = auth;
    }

    @PostMapping("/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public CommunityService.ReviewView review(@Valid @RequestBody ReviewRequest body, HttpServletRequest request) {
        return service.review(auth.requireUserId(request), body.tradeId(), body.rating(), body.comment());
    }

    @GetMapping("/reviews")
    public CommunityService.Page<CommunityService.ReviewView> reviews(@RequestParam long userId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return service.reviews(userId, page, size);
    }

    @GetMapping("/users/{id}/reputation")
    public CommunityService.ReputationView reputation(@PathVariable long id) {
        return service.reputation(id);
    }

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public CommunityService.ReportView report(@Valid @RequestBody ReportRequest body, HttpServletRequest request) {
        return service.report(auth.requireUserId(request), body.targetType(), body.targetId(), body.reason());
    }

    @GetMapping("/reports/mine")
    public CommunityService.Page<CommunityService.ReportView> myReports(HttpServletRequest request,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return service.myReports(auth.requireUserId(request), page, size);
    }

    @GetMapping("/admin/reports")
    public CommunityService.Page<CommunityService.ReportView> reports(HttpServletRequest request,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        auth.requireAdminId(request);
        return service.reports(status, page, size);
    }

    @PutMapping("/admin/reports/{id}/resolution")
    public CommunityService.ReportView resolveReport(@PathVariable long id, @Valid @RequestBody ResolutionRequest body,
            HttpServletRequest request) {
        return service.resolveReport(auth.requireAdminId(request), id, body.decision(), body.note(), body.action());
    }

    @PostMapping("/reports/{id}/appeals")
    @ResponseStatus(HttpStatus.CREATED)
    public CommunityService.AppealView appeal(@PathVariable long id, @Valid @RequestBody AppealRequest body,
            HttpServletRequest request) {
        return service.appeal(auth.requireUserId(request), id, body.reason());
    }

    @PutMapping("/admin/reports/{id}/goods-restoration")
    public CommunityService.GoodsStatusView restoreGoods(@PathVariable long id,
            @Valid @RequestBody RestorationRequest body, HttpServletRequest request) {
        return service.restoreGoods(auth.requireAdminId(request), id, body.note());
    }

    @GetMapping("/reports/{id}/appeals")
    public List<CommunityService.AppealView> reportAppeals(@PathVariable long id, HttpServletRequest request) {
        return service.reportAppeals(auth.requireUserId(request), id);
    }

    @GetMapping("/admin/appeals")
    public CommunityService.Page<CommunityService.AppealView> appeals(HttpServletRequest request,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        auth.requireAdminId(request);
        return service.appeals(status, page, size);
    }

    @PutMapping("/admin/appeals/{id}/resolution")
    public CommunityService.AppealView resolveAppeal(@PathVariable long id, @Valid @RequestBody AppealResolutionRequest body,
            HttpServletRequest request) {
        return service.resolveAppeal(auth.requireAdminId(request), id, body.decision(), body.note());
    }

    @GetMapping("/admin/reports/{id}/audit")
    public List<CommunityService.AuditView> audit(@PathVariable long id, HttpServletRequest request) {
        auth.requireAdminId(request);
        return service.auditTrail(id);
    }

    @PutMapping("/favorites/{goodsId}")
    public CommunityService.FavoriteView favorite(@PathVariable long goodsId, HttpServletRequest request) {
        return service.favorite(auth.requireUserId(request), goodsId);
    }

    @DeleteMapping("/favorites/{goodsId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfavorite(@PathVariable long goodsId, HttpServletRequest request) {
        service.unfavorite(auth.requireUserId(request), goodsId);
    }

    @GetMapping("/favorites")
    public CommunityService.Page<CommunityService.FavoriteView> favorites(HttpServletRequest request,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return service.favorites(auth.requireUserId(request), page, size);
    }

    @PostMapping("/wanted")
    @ResponseStatus(HttpStatus.CREATED)
    public CommunityService.WantedView wanted(@Valid @RequestBody WantedRequest body, HttpServletRequest request) {
        return service.wanted(auth.requireUserId(request), body.title(), body.description(), body.maxPrice(), body.category());
    }

    @GetMapping("/wanted")
    public CommunityService.Page<CommunityService.WantedView> wantedList(@RequestParam(required = false) String q,
            @RequestParam(required = false) String category, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.wantedList(q, category, page, size);
    }

    @PutMapping("/wanted/{id}/close")
    public CommunityService.WantedView closeWanted(@PathVariable long id, HttpServletRequest request) {
        return service.closeWanted(auth.requireUserId(request), id);
    }

    @PutMapping("/admin/users/{id}/status")
    public CommunityService.UserStatusView userStatus(@PathVariable long id, @Valid @RequestBody UserStatusRequest body,
            HttpServletRequest request) {
        return service.setUserStatus(auth.requireAdminId(request), id, body.status());
    }

    public record ReviewRequest(@NotNull @Positive Long tradeId, @NotNull @Min(1) @Max(5) Integer rating,
                                @NotBlank @Size(max = 1000) String comment) { }
    public record ReportRequest(@NotBlank @Pattern(regexp = "GOODS|USER|REVIEW") String targetType,
                                @NotNull @Positive Long targetId, @NotBlank @Size(max = 1000) String reason) { }
    public record ResolutionRequest(@NotBlank @Pattern(regexp = "RESOLVED|REJECTED") String decision,
                                    @NotBlank @Size(max = 1000) String note,
                                    @Pattern(regexp = "NONE|HIDE_GOODS|DISABLE_USER") String action) { }
    public record AppealRequest(@NotBlank @Size(max = 1000) String reason) { }
    public record RestorationRequest(@NotBlank @Size(max = 1000) String note) { }
    public record AppealResolutionRequest(@NotBlank @Pattern(regexp = "UPHELD|REJECTED") String decision,
                                          @NotBlank @Size(max = 1000) String note) { }
    public record WantedRequest(@NotBlank @Size(max = 100) String title, @NotBlank @Size(max = 2000) String description,
                                @NotNull @DecimalMin("0.00") @DecimalMax("9999999.99") @Digits(integer = 7, fraction = 2) BigDecimal maxPrice,
                                @Pattern(regexp = "TEXTBOOK|ELECTRONICS|DAILY|CLOTHING|OTHER") String category) { }
    public record UserStatusRequest(@NotNull @Min(0) @Max(1) Integer status) { }
}
