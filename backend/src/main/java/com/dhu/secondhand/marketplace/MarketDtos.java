package com.dhu.secondhand.marketplace;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class MarketDtos {
    private MarketDtos() { }

    public record GoodsWriteRequest(
            @NotBlank @Size(max = 100) String title,
            @NotBlank @Size(max = 5000) String description,
            @NotNull @DecimalMin("0.00") @DecimalMax("1000000.00")
            @Digits(integer = 7, fraction = 2) BigDecimal price,
            @Size(max = 20) String category,
            @Size(max = 9) List<@NotBlank @Size(max = 2048) String> imageUrls) { }

    public record ShelfRequest(@NotNull Boolean onSale) { }
    public record TradeRequest(@NotNull @Positive Long goodsId) { }

    public record GoodsView(long id, long sellerId, String title, String description,
                            BigDecimal price, String category, List<String> imageUrls, String status,
                            LocalDateTime createdAt, LocalDateTime updatedAt) { }

    public record TradeView(long id, long goodsId, long sellerId, long buyerId,
                            BigDecimal agreedPrice, String status, boolean buyerConfirmed,
                            boolean sellerConfirmed, LocalDateTime createdAt, LocalDateTime updatedAt) { }

    public record Page<T>(List<T> items, long total, int page, int size) { }
}
