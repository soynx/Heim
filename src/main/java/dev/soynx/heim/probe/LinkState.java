package dev.soynx.heim.probe;

/** Probe outcome of a single link; UNKNOWN until the first check has finished. */
public enum LinkState {
    UP,
    DOWN,
    UNKNOWN
}
