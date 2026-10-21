package com.dhu.secondhand.account.service;

public interface AccountClosureGuard {
    void checkCanClose(long userId);
    default void onClosed(long userId) { }
}
