package com.dhu.secondhand.marketplace;

import com.dhu.secondhand.common.SessionAuth;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import static com.dhu.secondhand.marketplace.MarketDtos.*;

@RestController
public class MarketplaceController {
    private final MarketplaceService service;
    private final SessionAuth auth;

    public MarketplaceController(MarketplaceService service, SessionAuth auth) {
        this.service = service;
        this.auth = auth;
    }

    @PostMapping("/api/goods")
    @ResponseStatus(HttpStatus.CREATED)
    public GoodsView create(@Valid @RequestBody GoodsWriteRequest body, HttpServletRequest request) {
        return service.create(auth.requireUserId(request), body);
    }

    @PutMapping("/api/goods/{id}")
    public GoodsView edit(@PathVariable long id, @Valid @RequestBody GoodsWriteRequest body, HttpServletRequest request) {
        return service.edit(auth.requireUserId(request), id, body);
    }

    @PostMapping("/api/goods/{id}/shelf")
    public GoodsView shelf(@PathVariable long id, @Valid @RequestBody ShelfRequest body, HttpServletRequest request) {
        return service.shelf(auth.requireUserId(request), id, body.onSale());
    }

    @GetMapping("/api/goods")
    public Page<GoodsView> list(@RequestParam(required = false) String keyword,
                                @RequestParam(required = false) String category,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return service.list(keyword, category, page, size, null);
    }

    @GetMapping("/api/goods/mine")
    public Page<GoodsView> mine(@RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        return service.list(null, null, page, size, auth.requireUserId(request));
    }

    @GetMapping("/api/goods/{id}")
    public GoodsView detail(@PathVariable long id) { return service.detail(id); }

    @GetMapping("/api/goods/{id}/management")
    public GoodsView management(@PathVariable long id, HttpServletRequest request) {
        return service.manage(auth.requireUserId(request), id);
    }

    @PostMapping("/api/trades")
    @ResponseStatus(HttpStatus.CREATED)
    public TradeView apply(@Valid @RequestBody TradeRequest body, HttpServletRequest request) {
        return service.apply(auth.requireUserId(request), body.goodsId());
    }

    @GetMapping("/api/trades")
    public Page<TradeView> trades(@RequestParam(defaultValue = "ALL") String role,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        return service.trades(auth.requireUserId(request), role, page, size);
    }

    @GetMapping("/api/trades/{id}")
    public TradeView trade(@PathVariable long id, HttpServletRequest request) {
        return service.trade(auth.requireUserId(request), id);
    }

    @PostMapping("/api/trades/{id}/accept")
    public TradeView accept(@PathVariable long id, HttpServletRequest request) {
        return service.accept(auth.requireUserId(request), id);
    }

    @PostMapping("/api/trades/{id}/confirm")
    public TradeView confirm(@PathVariable long id, HttpServletRequest request) {
        return service.confirm(auth.requireUserId(request), id);
    }

    @PostMapping("/api/trades/{id}/cancel")
    public TradeView cancel(@PathVariable long id, HttpServletRequest request) {
        return service.cancel(auth.requireUserId(request), id);
    }
}
