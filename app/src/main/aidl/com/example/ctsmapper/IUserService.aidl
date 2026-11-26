package com.example.ctsmapper;

import com.example.ctsmapper.IKeyCallback;

interface IUserService {
    void destroy() = 16777114; // required by Shizuku
    void start(IKeyCallback cb, boolean evdev) = 1;
    void stop() = 2;
    void configure(String component, boolean recover) = 3;
    boolean grantSecure(String pkg) = 4;
    // Puts the service back in enabled_accessibility_services right now (manual "Restore" button fallback).
    boolean restoreService(String component) = 5;
}
