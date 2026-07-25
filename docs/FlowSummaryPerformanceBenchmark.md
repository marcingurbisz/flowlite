# `/api/flows` summary performance benchmark

## Purpose

This benchmark compares read strategies for the two summary projections used by
`CockpitService.listFlows()`:

1. `DriverManagerDataSource` and the current two aggregate queries.
2. HikariCP and the current two aggregate queries.
3. HikariCP and one combined aggregate query.
4. HikariCP and preaggregated flow/stage counters, plus the indexed dynamic
   long-running query.

It also compares H2 in-memory and file storage and probes whether a covering
index helps the current aggregate shape.

The benchmark is a repeatable local diagnostic, not a replacement for JMH or a
Render production profile. Its purpose is to compare large relative effects
using the same deterministic data and verified result semantics.

## Running it

```bash
./gradlew benchmarkFlowSummary \
  -PbenchmarkArgs='--rows=50000,200000 --warmups=3 --samples=10 --concurrency=8 --samples-per-worker=3'
```

The tool:

- creates fresh H2 databases for each storage mode and row count;
- uses the same indexes as the application schema;
- disables H2's query-result cache so persistent Hikari connections do not get
  an artificial advantage over `DriverManagerDataSource`;
- checks that all four variants return identical flow and stage summaries;
- reports sequential and concurrent `p50`, `p95`, maximum latency, and
  throughput;
- prints the H2 query plans;
- reports approximate post-GC heap growth and file size.

The first implementation did not disable H2's query-result cache. It produced
impossible repeated-query timings around `0.03ms` for Hikari while
`DriverManagerDataSource` remained in the hundreds of milliseconds. Those
numbers were rejected and the benchmark was corrected before recording the
results below.

## Results

### 200,000 summary rows

The table contains local `p50` request latency in milliseconds. The concurrent
case uses eight workers and 24 measured operations.

| Storage | Variant | Sequential | Concurrency 8 |
|---|---|---:|---:|
| memory | DriverManager, two queries | 281.975 | 545.678 |
| memory | Hikari, two queries | 262.090 | 592.818 |
| memory | Hikari, one query | 244.800 | 485.980 |
| memory | Hikari, preaggregated | 13.002 | 17.697 |
| file | DriverManager, two queries | 467.020 | 4088.460 |
| file | Hikari, two queries | 483.367 | 4003.535 |
| file | Hikari, one query | 429.204 | 522.633 |
| file | Hikari, preaggregated | 10.857 | 15.060 |

At this size:

- Hikari alone did not materially improve the aggregate workload. Query work
  dominates connection creation in the local sequential case.
- One combined query gave a modest sequential improvement on memory H2 and a
  much larger concurrency improvement on file H2. The two separate scans are
  especially vulnerable to interleaving/contention in file mode.
- Preaggregation was the only variant that changed the scaling shape. It was
  about 20 times faster than the Hikari two-query variant on memory H2 and
  about 45 times faster on file H2 in the sequential comparison.
- Under eight concurrent callers, preaggregation reduced file-H2 `p50` from
  about four seconds to about 15 milliseconds.

### 50,000 summary rows

Sequential `p50` remained linear for the scan-based approaches:

| Storage | DriverManager, two queries | Hikari, two queries | Hikari, one query | Preaggregated |
|---|---:|---:|---:|---:|
| memory | 77.964 | 71.328 | 64.311 | 4.100 |
| file | 81.960 | 82.238 | 66.726 | 3.101 |

The fourfold growth from 50,000 to 200,000 rows produced approximately
fourfold latency growth for the memory scan variants. This supports the
conclusion that the aggregate scans, not the small Kotlin DTO mapping, dominate
large-dataset latency.

## Index experiment

The candidate covering index was:

```sql
create index idx_flowlite_instance_summary_flows_covering
on flowlite_instance_summary(
    cockpit_status,
    flow_id,
    stage,
    updated_at,
    external_retry_allowed,
    auto_retry_max_attempts,
    next_auto_retry_at
);
```

Findings:

- H2 continued to use a table scan for the current stage query with
  `cockpit_status not in ('Completed', 'Cancelled')`.
- Rewriting that predicate as an explicit positive `IN` list made H2 use the
  covering index.
- The indexed positive form improved memory-H2 `p50` from about `284ms` to
  `248ms` in a focused 200,000-row run, but worsened file-H2 `p50` from about
  `509ms` to `701ms`.
- In a separate full run, the covering index without the predicate rewrite was
  effectively neutral on memory H2 (`262.277ms` vs `262.187ms`) and file H2
  (`464.133ms` vs `461.546ms`).

The index is therefore not a robust fix for this endpoint. Both flow-wide
totals and the combined query still need to inspect all rows. A wide additional
index also increases write and storage cost. Indexing remains valuable for the
selective long-running query: H2 uses the existing
`idx_flowlite_instance_summary_cockpit_status` index for the status/time
predicate.

## H2 file versus memory

Approximate isolated-process footprint after seeding and forcing GC:

| Rows | memory heap delta | file heap delta | H2 file size |
|---:|---:|---:|---:|
| 50,000 | 35 MiB | 17 MiB | 43 MiB |
| 200,000 | 112 MiB | 48–55 MiB | 814–818 MiB |

These are coarse diagnostics rather than retained-size measurements, but the
direction is clear:

- file mode roughly halved the measured Java heap contribution;
- it moved a large amount of state to disk;
- it did not improve the current two-query read path;
- at 200,000 rows and eight concurrent callers, file mode made that path much
  worse (`~4s` versus `~0.6s` `p50`);
- once the endpoint used one query or preaggregated counters, the file/memory
  latency gap became much smaller.

Switching the test instance to H2 file can be considered as a heap-pressure
experiment, but it is not an endpoint-performance fix by itself.

## Recommendation

1. Implement the one-query projection first if a small, low-migration
   improvement is wanted. It preserves the current source table and removes one
   aggregate scan/connection boundary.
2. Use preaggregated flow and stage counters for predictable performance with
   genuinely large datasets. Keep the dynamic long-running count as a selective
   indexed query, or define a fixed threshold if it also needs preaggregation.
3. Do not add the tested covering index to the application schemas. Its benefit
   was storage-mode dependent and it cannot remove the full-data aggregation.
4. Do not treat Hikari as the primary fix. A bounded pool is still useful for
   backpressure and predictable connection lifecycle, but it did not reduce the
   scan cost in this benchmark.
5. Do not switch to H2 file solely to make `/api/flows` faster. If it is tested
   on Render to reduce heap pressure, pair it with the one-query or
   preaggregated design and measure disk growth.

Before productionizing preaggregation, separately benchmark the additional
write cost and define transactional update, rebuild, and consistency-repair
semantics for the counter tables.

## Limitations

- The dataset contains only `flowlite_instance_summary`, not the complete
  domain/history/tick workload from the showcase application.
- Reads run against a deterministic synthetic status/stage distribution.
- The benchmark does not run concurrent writers.
- Local CPU, filesystem, and memory limits differ from Render.
- Measurements use warm databases and should be interpreted relatively.
