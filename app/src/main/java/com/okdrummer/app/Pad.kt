package com.okdrummer.app

/** One drumpad. Order = grid order: top row left→right, then bottom row (PROJECT.md §1). */
enum class Pad(val label: String, val asset: String) {
    LOW_TOM("Low tom", "tom_low"),
    MID_TOM("Mid tom", "tom_mid"),
    HIGH_TOM("High tom", "tom_high"),
    CYMBAL("Cymbal", "cymbal"),
    KICK("Kick", "kick"),
    SNARE("Snare", "snare"),
    HAT_CLOSED("Closed hat", "hat_closed"),
    HAT_OPEN("Open hat", "hat_open"),
}
