package com.dhu.secondhand.identity.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface RegistrationEmailCodeMapper {

    @Insert("""
            INSERT INTO registration_email_code
                (email, code_hash, expires_at, last_sent_at)
            VALUES (#{email}, '', #{now}, #{now})
            ON DUPLICATE KEY UPDATE email = email
            """)
    int ensureExists(@Param("email") String email, @Param("now") LocalDateTime now);

    @Select("""
            SELECT last_sent_at FROM registration_email_code
            WHERE email = #{email} AND code_hash <> '' FOR UPDATE
            """)
    LocalDateTime lockLastSentAt(@Param("email") String email);

    @Update("""
            UPDATE registration_email_code
            SET code_hash = #{codeHash}, expires_at = #{expiresAt},
                last_sent_at = #{sentAt}, failed_attempts = 0, consumed_at = NULL
            WHERE email = #{email}
            """)
    int replaceCode(@Param("email") String email, @Param("codeHash") String codeHash,
                    @Param("expiresAt") LocalDateTime expiresAt,
                    @Param("sentAt") LocalDateTime sentAt);
}
