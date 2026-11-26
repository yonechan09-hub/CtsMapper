package com.example.ctsmapper;

oneway interface IKeyCallback {
    // type: 1 = EV_KEY, 5 = EV_SW. value: 1 = down/on, 0 = up/off.
    void onEvent(String path, String name, int type, int code, int value);
    void onInfo(String msg);
}
