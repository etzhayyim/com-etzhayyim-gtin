# etzhayyim-project-gtin

gtin.etzhayyim.com — 全世界の商品 identity を GTIN family で正規化する canonical product actor。価格や merchant offer は持たず、商品そのものの DID を持つ。

## Role

- GTIN-14, JAN, UPC, EAN を canonical product identity に寄せる
- 同一商品の alias code を束ねる
- brand / category / pack size を product master として持つ
- downstream actor (`kakaku`, `omise`, `serial`) に canonical product DID を供給する

## Architecture

```
Browser / API client
  → /xrpc/com.etzhayyim.gtin.*
    ↓
  did:web:etzhayyim.com:actor:gtin
    ├─ Catalog: registerProduct / updateProduct / lookupProduct
    ├─ Identifier Registry: validateGtin / resolveAlias / mergeAliases
    ├─ Brand Registry: registerBrand / matchBrand
    └─ Quality: detectDuplicate / reviewPackagingSplit
```

## Data Model

価格は持たない。`gtin` actor の責務は global product identity だけ。

### Collections

| Collection | Role | Key fields |
|---|---|---|
| `com.etzhayyim.gtin.product` | canonical global product | `productId, gtin, jan, upc, ean, name, brand, model, packSize, category` |
| `com.etzhayyim.gtin.alias` | identifier alias set | `canonicalProductId, codeType, codeValue` |
| `com.etzhayyim.gtin.brand` | canonical brand | `brandId, name, ownerDid, country` |
| `com.etzhayyim.gtin.category` | product taxonomy | `categoryId, name, parentId` |

## DID Patterns

controller は `did:web:etzhayyim.com:actor:gtin`（identity.edn / manifest.edn :actor/did が正、旧 `did:web:gtin.etzhayyim.com` は legacy-did）。

| Entity | DID pattern | Notes |
|---|---|---|
| Product | `did:web:etzhayyim.com:actor:gtin:product:{product_key}` | canonical trade item |
| Brand | `did:web:etzhayyim.com:actor:gtin:brand:{brand_key}` | canonical brand |
| Alias | `did:web:etzhayyim.com:actor:gtin:alias:{code_type}:{code_value}` | GTIN-family alias edge |

## Path Resolve

`product_key` は次の優先順:

1. `gtin`
2. `jan`
3. `upc`
4. `ean`
5. `brand + model + packSize`

Normalization:

- digits only for barcode-family identifiers
- left-pad / canonicalize to GTIN-14 where appropriate, while retaining source code form
- same code family mapping to same trade item should resolve to one canonical product DID
- different pack sizes must split DID even if brand + model match

## Boundary With Other Actors

- `gtin.etzhayyim.com`: 商品 identity
- `kakaku.etzhayyim.com`: merchant-specific offer / price history / comparison
- `serial.etzhayyim.com`: SGTIN など個品シリアル
- `omise.etzhayyim.com`: marketplace catalog / order / seller state

`kakaku` で `productId = jan_4902370553023` のように扱っていたものは、本来 `gtin` 側の canonical DID を upstream に持てる。

## Ingest Path Plan

### Resident LangGraph Path

ADR-2605091200 makes product ingest a resident LangGraph loop rather than a
retailer-only CronJob. The authoritative per-product enrichment graph is:

`discover_candidates -> fetch_official_pages -> fetch_merchant_pages -> extract_product_facts -> resolve_brand_owner -> resolve_canonical_product -> match_offers -> quality_gate -> write_graph`

Official manufacturer / brand pages are first-class evidence for product
identity, specs, MPN, pack size, images, and brand ownership. Retailer pages
remain evidence for merchant offer, price, stock, and delivery only. Webfetch
(`site.crawlPage`), intel/entity resolution, and LLM inference run as explicit
LangGraph nodes with checkpointed state; they are not hidden side effects in a
scraper.

### Live Path

`site.etzhayyim.com -> gtin.etzhayyim.com -> kakaku.etzhayyim.com`

1. `site.etzhayyim.com` が merchant page を `crawlPage` で取得する
2. page / markdown / JSON-LD から `gtin`, `jan`, `upc`, `ean` 候補を抽出する
3. `com.etzhayyim.gtin.validateGtin` で code 種別判定と check digit 検証を行う
4. `com.etzhayyim.gtin.lookupProduct` で canonical product DID を解決する
5. 未登録なら `com.etzhayyim.gtin.registerProduct` で global product identity を作る
6. merchant-specific offer は `com.etzhayyim.apps.kakaku.ingestOfferFromUrl` または `upsertOffer` に流す

### Batch Path

`common crawler ingest -> gtin.etzhayyim.com -> kakaku.etzhayyim.com`

1. Common Crawl / OpenFoodFacts / merchant feed dump から URL または product JSON を見つける
2. barcode candidate を抽出する
3. `gtin.validateGtin` で正規化する
4. `gtin.lookupProduct/registerProduct` で canonical product を作る
5. merchant / price / stock がある場合だけ `kakaku.upsertOffer` に流す

### Current Implementation

- `methods/mesh.clj` — KOTOBA Mesh entry（kqe-assert!/kqe-query、cap/kqe host-import）。GTIN→product edge を主張（observatory on-kse pattern、ADR-2606230001 §4）。
- `src/gtin/murakumo.cljc` — manifest-migration scaffold（cell-plan / gate 検証）。
- barcode 抽出優先は `JSON-LD -> labeled text (GTIN/JAN/UPC/EAN/barcode) -> unlabeled 8/12/13/14 digit`。kakaku / omise / serial は downstream で canonical product DID を消費（merchant offer / price は gtin actor の責務外）。
- GTIN actor 本体の 4-collection catalog と methods（validate/lookup/register）は bridge ADR で設計を固定し追加する。check-digit は GS1 mod-10（GTIN-8/12/13/14）、uchiwake_edn.cljc と 1:1。

### Extraction Priority

barcode extraction は次の順で見る:

1. `schema.org/Product` / JSON-LD
2. Open Graph / meta tags
3. visible text
4. feed / API fields
5. LLM fallback

### Responsibility Split

- `site.etzhayyim.com`: live acquisition
- common crawler: large-scale discovery and backfill
- `gtin.etzhayyim.com`: canonical product identity
- `kakaku.etzhayyim.com`: merchant offer and price history

## Examples

| Input | Resolved DID |
|---|---|
| JAN `4902102139496` | `did:web:etzhayyim.com:actor:gtin:product:jan_4902102139496` |
| GTIN `00194253396062` | `did:web:etzhayyim.com:actor:gtin:product:gtin_00194253396062` |
| UPC `012345678905` | `did:web:etzhayyim.com:actor:gtin:product:upc_012345678905` |

## Notes

- global coverage target in repo is `1_000_000_000 GTIN barcodes`
- seed / coverage 上の canonical actor は `did:web:etzhayyim.com:actor:gtin`（identity.edn / manifest.edn :actor/did と一致）
- merchant price comparison を直接ここに入れない

## Standalone repository contract

- `kotoba.app.edn` is the canonical deployment manifest. Component and placement values remain
  structured EDN; do not encode nested EDN as strings.
- `schema.edn` and every path referenced by the deployment manifest are owned by this repository.
- `bb run_tests.clj` must pass from a standalone checkout. Do not restore `20-actors` or
  superproject-relative classpath assumptions.
