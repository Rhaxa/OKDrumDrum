package com.okdrumdrum.app

/** Sound banks (PROJECT.md §1 Banks). Each bank is an independent full sound set;
 *  `accent` tints pads, dots, and the options panel while the bank is active. */
enum class Bank(val accent: Long) {
    A(0xFF1DA8E8), // cyan-blue
    B(0xFF46D140), // green
    C(0xFFF7931E), // orange
    D(0xFFF02FC2), // magenta
}
