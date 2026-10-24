package net.coreprotect.consumer;
public record LogEntry(String world, int x, int y, int z, String actor, String action, String data) {}