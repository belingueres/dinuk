# dinuk

A transparent JDBC driver that rewrites SQL at the client so applications can keep
querying a single logical table while the data is physically distributed across
many partitioned tables.

Write your application against a **view** (for example `orders`) as if it were one
ordinary table. `dinuk` intercepts every statement at the JDBC layer, determines the
value of the partition key in the query, and rewrites the SQL to target the correct
**physical table** (`orders_1`, `orders_2`, …). No ORM changes, no query rewriting by
hand.

## Why

Not every database offers native table partitioning. Lightweight and embedded
engines — SQLite, HSQLDB, H2, Apache Derby, and Firebird before 3.0 — have no
partitioning support at all, and neither do desktop-oriented products such as
Microsoft Access, Valentina DB, or Empress. And among the databases that do support
it, some commercial vendors only make partitioning available as a paid add-on or in
more expensive license editions.

`dinuk` sidesteps this by moving partitioning into the JDBC layer: your application
keeps querying a single logical table (a view), while `dinuk` inspects every
statement, extracts the partition key value, and rewrites it to target the correct
physical table. You get partitioned behavior on any of the databases above — with no
native feature required and no extra licensing cost — and the strategy behaves
identically regardless of the database underneath.

### Example: table-per-tenant multitenancy

A practical use case is a **table-per-tenant** multitenancy strategy. The partition
key is the tenant identifier: each customer owns a dedicated physical table, while a
single global `customers` table lists the tenants.

```properties
viewNamePrefix=products
partitionType=key
partitionKey=customer_id
```

```sql
-- global table (not partitioned, passed through unchanged)
CREATE TABLE customers (id INT PRIMARY KEY, name VARCHAR(100));

-- one physical table per customer
CREATE TABLE products_123 (name VARCHAR(100), price DECIMAL(10, 2));
CREATE TABLE products_456 (name VARCHAR(100), price DECIMAL(10, 2));

-- logical view used by the application
CREATE VIEW products AS
SELECT 123 AS customer_id, name, price FROM products_123
UNION ALL
SELECT 456 AS customer_id, name, price FROM products_456;
```

`dinuk` routes each statement to the tenant's own table:

| Original SQL                                                                   | Rewritten SQL                                                    |
| ------------------------------------------------------------------------------ | ---------------------------------------------------------------- |
| `SELECT name, customer_id FROM products WHERE customer_id = 123`               | `SELECT name, 123 customer_id FROM products_123`                 |
| `INSERT INTO products (customer_id, name, price) VALUES (456, 'Widget', 9.99)` | `INSERT INTO products_456 (name, price) VALUES ('Widget', 9.99)` |
| `SELECT * FROM customers WHERE id = 456`                                       | `SELECT * FROM customers WHERE id = 456`                         |

The tenant id can also be supplied as a prepared statement parameter, so a session
pinned to one customer simply binds it once:

```sql
SELECT name, customer_id FROM products WHERE customer_id = ?   -- binds 456 → products_456
```

## Features

- **Transparent JDBC driver.** Register `DinukDriver` and connect with a
  `jdbc:dinuk:<real-url>` URL; the underlying driver is used for the real connection.
- **Statement rewriting** for `SELECT`, `INSERT`, `UPDATE` and `DELETE`.
- **Cross-partition row moves.** An `UPDATE` that assigns a new value to the
  partition key itself (e.g. `UPDATE orders SET id=5 WHERE id=1`) is rewritten into
  an atomic move — copy into the new partition, delete from the old — since a single
  `UPDATE` cannot span two physical tables. Works for all four partitioning
  strategies, with `PreparedStatement` or plain `Statement`.
- **Four partitioning strategies**:
  - **`key`** — physical table is `<table>_<value>` (e.g. `id=2` → `orders_2`).
  - **`range`** — declarative `[low, high]` intervals that map values to physical
    tables (e.g. `[0,10]` → `orders_0`, `[11,20]` → `orders_1`).
  - **`list`** — declarative lists of discrete values that map to physical tables
    (e.g. `us,ca,mx` → `orders_na`, `uk,ie` → `orders_eu`).
  - **`hash`** — `N` partitions, and a value routes to `orders_MOD(value, N)`
    (e.g. `MOD(5,4)=1` → `orders_1`).
- **Multi-table configurations.** Declare any number of partitioned tables
  (`orders`, `users`, `audit`, …) in the same configuration, each with its own
  partition type and key.
- **JOINs**, including inner/left/right/full `JOIN ... ON`, `CROSS JOIN`, bare
  `JOIN`, and comma joins, across two or more partitioned tables.
- **Set operations** `UNION`, `INTERSECT` and `EXCEPT`.
- **`FOR UPDATE`** clause is preserved.
- **Parameterized statements.** `?` placeholders in the partition key are resolved
  at execution time, when the real value is known, with automatic parameter index
  remapping.
- **Partition key kept in results.** The partition key column is preserved in the
  `ResultSet` under its original name. Value-mapped modes (`range`, `list`, `hash`)
  reference the actual column, which the physical tables store; `key` mode substitutes
  the literal value under an alias, so existing `rs.getInt("id")` calls keep working
  even when the physical table has no `id` column.
- **Statement caching.** Rewritten and resolved statements are cached (Caffeine),
  so repeated statements skip re-parsing.

## How it works

`dinuk` is a JDBC `Driver` that wraps another JDBC driver. On `connect()` it builds
a `Configuration` from the connection properties, parses every statement with
[Druid's SQL AST parser](https://github.com/alibaba/druid), walks the AST, and
produces a rewritten statement targeting the physical table that holds the partition
key value.

- With **plain `Statement`** the SQL is rewritten eagerly before execution.
- With **`PreparedStatement`** the SQL is rewritten once, and when the partition key
  is a `?` placeholder the physical table is only resolved at execution time from the
  bound parameter value.

### Examples

Configuration (key partitioning):

```properties
viewNamePrefix=orders
partitionType=key
partitionKey=id
```

| Original SQL                                                      | Rewritten SQL                                                |
| ----------------------------------------------------------------- | ------------------------------------------------------------ |
| `SELECT name, id, amount FROM orders WHERE id=2`                  | `SELECT name, 2 id, amount FROM orders_2`                    |
| `UPDATE orders SET name='Bob' WHERE id=2`                         | `UPDATE orders_2 SET name='Bob'`                             |
| `DELETE FROM orders WHERE id=2 AND status='inactive'`             | `DELETE FROM orders_2 WHERE status='inactive'`               |
| `INSERT INTO orders (id, name, amount) VALUES (1, 'Alice', 2.34)` | `INSERT INTO orders_1 (name, amount) VALUES ('Alice', 2.34)` |

Configuration (range partitioning):

```properties
viewNamePrefix=orders
partitionType=range
partitionKey=id
orders.range.1.low=0
orders.range.1.high=10
orders.range.1.table=orders_0
orders.range.2.low=11
orders.range.2.high=20
orders.range.2.table=orders_1
```

| Original SQL                               | Rewritten SQL                                  |
| ------------------------------------------ | ---------------------------------------------- |
| `SELECT name, id FROM orders WHERE id=5`   | `SELECT name, id FROM orders_0 WHERE id=5`   |
| `SELECT name, id FROM orders WHERE id=15`  | `SELECT name, id FROM orders_1 WHERE id=15`  |
| `UPDATE orders SET amount=9.9 WHERE id=12` | `UPDATE orders_1 SET amount=9.9 WHERE id=12`   |

Configuration (list partitioning):

```properties
viewNamePrefix=orders
partitionType=list
partitionKey=region
orders.list.1.values=us,ca,mx
orders.list.1.table=orders_na
orders.list.2.values=uk,ie
orders.list.2.table=orders_eu
```

| Original SQL                                            | Rewritten SQL                                                   |
| ------------------------------------------------------- | --------------------------------------------------------------- |
| `SELECT name, region FROM orders WHERE region='us'`     | `SELECT name, region FROM orders_na WHERE region='us'`          |
| `INSERT INTO orders (region, name) VALUES ('uk', 'A')`  | `INSERT INTO orders_eu (region, name) VALUES ('uk', 'A')`       |

Configuration (hash partitioning):

```properties
viewNamePrefix=orders
partitionType=hash
partitionKey=id
orders.hash.partitions=4
```

Physical tables are `orders_0`, `orders_1`, `orders_2`, `orders_3`, and a value
routes to `orders_MOD(value, 4)`:

| Original SQL                                       | Rewritten SQL                                           |
| -------------------------------------------------- | ------------------------------------------------------- |
| `SELECT name, id FROM orders WHERE id=5`           | `SELECT name, id FROM orders_1 WHERE id=5`              |
| `SELECT name, id FROM orders WHERE id=8`           | `SELECT name, id FROM orders_0 WHERE id=8`              |
| `INSERT INTO orders (id, name) VALUES (6, 'A')`    | `INSERT INTO orders_2 (id, name) VALUES (6, 'A')`       |

## Moving a row across partitions

A normal `UPDATE` only ever touches one physical table. But when the `SET` clause
assigns a **new value to the partition key itself**, the row may belong in a
different physical table afterward — a single `UPDATE` statement cannot span two
tables. `dinuk` detects this case and rewrites the statement into an atomic **move**:
it copies the row into the target partition with an `INSERT ... SELECT` (applying
every other `SET` column along the way), then deletes it from the source partition,
running both statements in one transaction on the underlying connection. If the new
and old key values resolve to the *same* physical table, no move is needed and the
driver falls back to a plain in-place `UPDATE`.

```java
String sql = "UPDATE orders SET id = ?, name = ? WHERE id = ?";
try (PreparedStatement stmt = conn.prepareStatement(sql)) {
    stmt.setInt(1, 5);        // new id -> new partition
    stmt.setString(2, "Bob");
    stmt.setInt(3, 1);        // old id -> current partition
    stmt.executeUpdate();     // the row for id=1 is moved into the id=5 partition
}
```

This works with any mix of bound parameters and literals in the `SET` and `WHERE`
clauses, with either `PreparedStatement` or plain `Statement`, and across all four
partitioning strategies:

| Configuration | Example                                                     | Effect                                                      |
| ------------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| `key`         | `UPDATE orders SET id=5 WHERE id=1`                           | Row moves from `orders_1` to `orders_5`.                     |
| `range`       | `UPDATE orders SET amount=99 WHERE id=5 AND amount=3`         | Row moves from the `[0,10]` table to the `[11,100]` table.   |
| `list`        | `UPDATE orders SET region='uk' WHERE id=1 AND region='us'`    | Row moves from `orders_na` to `orders_eu`.                   |
| `hash`        | `UPDATE orders SET id=2 WHERE id=1` (2 partitions)            | Row moves from `orders_1` to `orders_0`.                     |

Notes:

- The move runs inside a transaction on the connection: if the driver's
  `autoCommit` is on, it is temporarily turned off for the move and restored
  afterward; if the insert into the target partition fails, nothing is deleted from
  the source.
- Only a partition key value that is a literal or a `?` placeholder can be routed —
  an arbitrary expression (e.g. `SET id = id + 1`) cannot be proven to target a
  table ahead of time, so the statement is left unchanged in that case.
- Other columns in the `SET` clause must also be literals or `?` placeholders (not
  arbitrary expressions), so their new values can be carried into the
  `INSERT ... SELECT`.

## Getting started

### Requirements

- Java 8+ (the driver itself); the test suite builds on Java 17.
- Maven 3.x.

### Maven

Not yet published to a public repository — install it locally first:

```bash
mvn install
```

Then depend on it:

```xml
<dependency>
  <groupId>io.github.belingueres.dinuk</groupId>
  <artifactId>dinuk</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Connecting

Load the driver (or let `DriverManager` discover it via the JDBC `META-INF` service)
and connect using a `jdbc:dinuk:` URL that wraps the real URL:

```java
Properties props = new Properties();
props.setProperty("viewNamePrefix", "orders");
props.setProperty("partitionType", "key");
props.setProperty("partitionKey", "id");

Connection conn = DriverManager.getConnection(
        "jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", props);
```

The database type and the underlying driver are detected from the wrapped URL, so
the same properties apply to any JDBC database supported by Druid (HSQLDB, Informix,
MySQL, PostgreSQL, Oracle, …).

Statements on that connection are rewritten transparently:

```java
// runs against orders_2, the ResultSet still exposes column "id"
try (Statement stmt = conn.createStatement();
     ResultSet rs = stmt.executeQuery(
             "SELECT name, id, amount FROM orders WHERE id = 2")) {
    while (rs.next()) {
        System.out.println(rs.getString("name") + " / " + rs.getInt("id"));
    }
}
```

Parameterized statements work the same way, including when the partition key is a
placeholder:

```java
String sql = "SELECT name, id, amount FROM orders WHERE id = ? AND name = ?";
try (PreparedStatement stmt = conn.prepareStatement(sql)) {
    stmt.setInt(1, 2);     // id → routes to orders_2
    stmt.setString(2, "Bob");
    try (ResultSet rs = stmt.executeQuery()) {
        // ...
    }
}
```

### Sample apps

For complete, runnable Spring Boot examples covering all four partition types
across two different domains, see the
[dinuk-samples](https://github.com/belingueres/dinuk-samples) repository.

## Configuration reference

All properties are read from the `Properties` passed to
`DriverManager.getConnection(...)`. Each property may also be prefixed with
`dinuk.` (e.g. `dinuk.viewNamePrefix`).

### Single-table configuration

| Property         | Description                                            | Example  |
| ---------------- | ------------------------------------------------------ | -------- |
| `viewNamePrefix` | Logical (view) table name that triggers rewriting      | `orders` |
| `partitionType`  | Partitioning strategy: `key`, `range`, `list` or `hash` | `key`    |
| `partitionKey`   | Column name that holds the partition key               | `id`     |

### Range entries (only when `partitionType=range`)

Ranges are numbered starting at 1: `orders.range.1.*`, `orders.range.2.*`, …

| Property                   | Description                                          |
| -------------------------- | ---------------------------------------------------- |
| `<prefix>.range.<n>.low`   | Lower bound, **inclusive**. Omit for unbounded (−∞). |
| `<prefix>.range.<n>.high`  | Upper bound, **inclusive**. Omit for unbounded (+∞). |
| `<prefix>.range.<n>.table` | Physical table for values in `[low, high]`.          |

Semantics:

- Bounds are inclusive on both sides.
- Values are compared numerically when both sides are numeric, otherwise
  lexicographically (so date-like or code string ranges work too).
- A missing bound means open-ended: `orders.range.1.high=10` covers `id <= 10`;
  `orders.range.2.low=11` covers `id >= 11`.
- Overlapping ranges are detected at startup (a warning is logged) and the **first
  match wins**.
- If a value matches no range, the statement is left unchanged (no rewriting).

### List entries (only when `partitionType=list`)

Lists are numbered starting at 1: `orders.list.1.*`, `orders.list.2.*`, …

| Property                    | Description                                     |
| --------------------------- | ----------------------------------------------- |
| `<prefix>.list.<n>.values`  | Comma-separated discrete values for this list.  |
| `<prefix>.list.<n>.table`   | Physical table for the values in this list.     |

Semantics:

- Values are compared numerically when both sides are numeric (so `1` matches a list
  containing `1.0`), otherwise with exact, **case-sensitive** string equality.
- Whitespace around values is ignored (`us, ca, mx` and `us,ca,mx` are equivalent).
- A value present in more than one list is detected at startup (a warning is logged)
  and the **first match wins**.
- If a value matches no list, the statement is left unchanged (no rewriting).

### Hash partitions (only when `partitionType=hash`)

| Property                    | Description                                              |
| --------------------------- | -------------------------------------------------------- |
| `<prefix>.hash.partitions`  | Number of physical partitions `N` (a positive integer).  |

Semantics (mirroring MySQL `PARTITION BY HASH`):

- A value routes to `<prefix>_<index>` where `index = MOD(value, N)`, always in
  `[0, N)` (computed with floored division, so negative values stay in range).
- Decimal values are truncated toward zero before the modulus (`5.9` → `5`).
- Values that are not numeric match no partition: deferred values fail at
  execution time, literal values leave the statement unchanged.
- The physical tables are derived from the prefix (`orders_0`, `orders_1`, …); no
  per-partition configuration is needed.

### Multiple partitioned tables

Add more tables with a numeric suffix; each table gets its own range properties
keyed by its own prefix:

```properties
viewNamePrefix=orders
partitionType=range
partitionKey=id
orders.range.1.high=10
orders.range.1.table=orders_0
orders.range.2.low=11
orders.range.2.table=orders_1

viewNamePrefix2=users
partitionType2=key
partitionKey2=uid

viewNamePrefix3=audit
partitionType3=range
partitionKey3=year
audit.range.1.high=2023
audit.range.1.table=audit_2023
audit.range.2.low=2024
audit.range.2.table=audit_2024

viewNamePrefix4=orders_eu
partitionType4=list
partitionKey4=region
orders_eu.list.1.values=uk,ie
orders_eu.list.1.table=orders_ukie

viewNamePrefix5=payments
partitionType5=hash
partitionKey5=payment_id
payments.hash.partitions=8
```

`users` is routed by key (`uid=7` → `users_7`), `audit` by year range,
`orders_eu` by a list of discrete regions, and `payments` by hash
(`payment_id` → `payments_0` … `payments_7`), all from the same connection.

### Caching

| Property            | Description                                       | Default |
| ------------------- | ------------------------------------------------- | ------- |
| `cache.maximumSize` | Maximum number of entries in the statement caches | `1000`  |

## Behavioral notes

- **`key` vs `range`/`list`/`hash` physical schema.** In `key` mode the partition
  key value is encoded in the table name, so physical tables typically have **no**
  key column: the key column is removed from `INSERT`s and the `WHERE`/`SELECT` key
  references are removed (the value is kept in the result set through the column
  alias). In `range`, `list` and `hash` mode the physical tables **store** the key
  column, so it is kept in `INSERT`s and `WHERE` conditions — only the target table
  changes.
- **`SELECT *` (and qualified `*`).** Statements whose select list contains a
  wildcard are sent through **unchanged**, because the partition key cannot be
  aliased into the result set.
- **Non-partitioned statements.** SQL that does not reference a configured table is
  passed through to the underlying driver untouched.
- **Prepared statement parameter remapping.** When the partition key column or
  condition is removed, remaining `?` parameters are re-indexed automatically so the
  values bound by the application line up with the rewritten statement.

## Building and testing

```bash
mvn test
```

The test suite covers SQL rewriting with Druid's parser (no database needed) plus
end-to-end tests against an in-memory HSQLDB.

## License

[Apache License, Version 2.0](LICENSE)
