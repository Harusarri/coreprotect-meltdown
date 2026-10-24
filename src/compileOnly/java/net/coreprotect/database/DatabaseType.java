package net.coreprotect.database;
public enum DatabaseType {
    SQLITE, MYSQL, CLICKHOUSE, DUCKDB;
    public boolean isClickHouse() { return false; }
    public boolean isDuckDb() { return false; }
    public boolean isSqlite() { return false; }
    public boolean isMysql() { return false; }
}