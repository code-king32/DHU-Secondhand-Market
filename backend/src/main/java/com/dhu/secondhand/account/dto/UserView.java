package com.dhu.secondhand.account.dto;

import com.dhu.secondhand.account.entity.User;
import java.time.LocalDateTime;

public record UserView(Long id, String email, String nickname, boolean emailVerified,
                       Integer status, String role, LocalDateTime createdAt) {
    public static UserView from(User user) {
        return new UserView(user.getId(), user.getEmail(), user.getNickname(),
                Integer.valueOf(1).equals(user.getEmailVerified()), user.getStatus(),
                user.getRole(), user.getCreatedAt());
    }
}
