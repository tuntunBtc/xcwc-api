package com.yanji;
public interface AdminAccess {
    record Actor(long id, String username) {}
    Actor require(String permission);
}
