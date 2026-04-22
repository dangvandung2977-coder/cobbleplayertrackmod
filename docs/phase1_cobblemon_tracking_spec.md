# PHASE 1 — DEFINE DATA + API SPEC  
## Cobblemon Multi-Server Tracking Platform

**Stack mục tiêu**
- Minecraft: **1.21.1**
- Loader: **Fabric**
- Cobblemon: **1.7.3**
- Kiến trúc: **Server mod → Backend API → Database → Web**

---

# 1. Mục tiêu của Phase 1

Phase 1 dùng để **chốt hợp đồng dữ liệu** trước khi code backend, mod, và web.

Sau phase này phải có đủ:

1. Danh sách entity chính của hệ thống  
2. JSON shape chuẩn cho từng loại dữ liệu  
3. API contract rõ ràng cho mod và web  
4. Database schema ở mức đủ để build backend  
5. Quy tắc validation cơ bản  
6. Bộ payload mẫu để test backend bằng mock data  

## Vì sao phase này quan trọng

Nếu bỏ qua phase này, rất dễ gặp các lỗi sau:
- Mod gửi lên một kiểu dữ liệu
- Backend lưu một kiểu khác
- Web cần hiển thị một kiểu khác
- Về sau phải sửa đồng loạt 3 lớp

Nói ngắn gọn: **Phase 1 là bước khóa structure của toàn hệ thống**.

---

# 2. Phạm vi V1 đã chốt

## V1 phải làm được
- Hiển thị danh sách **server Cobblemon đang dùng mod**
- Click vào 1 server để xem **toàn bộ player đã từng tham gia**
- Click vào 1 player để xem:
  - tên
  - UUID
  - skin
  - party hiện tại
  - chi tiết từng Pokémon:
    - item
    - IV
    - EV
    - ability
    - moveset
  - Pokédex:
    - species đã mở / đã bắt → hiện ảnh
    - species chưa mở → hiện `?`

## V1 chưa làm
- Realtime battle state
- Match history sâu
- Live queue / elo / rank
- Admin dashboard phức tạp
- Write-back từ web vào server

---

# 3. Kiến trúc tổng thể

```txt
Minecraft Server (Fabric + CobbleSyncBridge)
        ↓
HTTP sync
        ↓
Backend API (FastAPI)
        ↓
PostgreSQL
        ↓
Next.js Web Dashboard
```

## Vai trò từng lớp

### 3.1 Fabric mod
- Đọc dữ liệu thật từ server Cobblemon
- Chuyển object nội bộ thành JSON sạch
- Gửi dữ liệu lên backend

### 3.2 Backend API
- Nhận request từ mod
- Validate dữ liệu
- Lưu DB
- Cung cấp API cho web query

### 3.3 Database
- Lưu danh sách server
- Lưu player
- Lưu party snapshot gần nhất
- Lưu trạng thái Pokédex

### 3.4 Web
- Không đọc dữ liệu Minecraft trực tiếp
- Chỉ đọc từ backend API
- Hiển thị server → player → party/pokedex

---

# 4. Danh sách entity chính

Hệ thống V1 dùng 4 entity lõi:

1. **Server**
2. **Player**
3. **Party Pokémon**
4. **Pokédex Entry**

---

# 5. Định nghĩa dữ liệu chi tiết

## 5.1 Server

Server là đối tượng đại diện cho một Minecraft server đang dùng mod bridge.

### Trường dữ liệu đề xuất

| Field | Type | Required | Ý nghĩa |
|---|---|---:|---|
| id | string | auto | ID nội bộ backend |
| name | string | yes | Tên server để hiển thị |
| ip | string | no | IP hoặc display endpoint |
| mcVersion | string | yes | Phiên bản Minecraft |
| cobblemonVersion | string | yes | Phiên bản Cobblemon |
| modVersion | string | yes | Phiên bản mod bridge |
| online | boolean | yes | Server có còn heartbeat gần đây không |
| lastSeen | datetime | yes | Thời điểm nhận heartbeat gần nhất |
| createdAt | datetime | auto | Thời điểm đăng ký |
| updatedAt | datetime | auto | Thời điểm cập nhật gần nhất |

### JSON mẫu

```json
{
  "id": "srv_8b4f1d",
  "name": "Cobbleverse Alpha",
  "ip": "play.cobbleverse.net",
  "mcVersion": "1.21.1",
  "cobblemonVersion": "1.7.3",
  "modVersion": "1.0.0",
  "online": true,
  "lastSeen": "2026-04-17T12:00:00Z",
  "createdAt": "2026-04-17T10:00:00Z",
  "updatedAt": "2026-04-17T12:00:00Z"
}
```

### Ghi chú thiết kế
- `id` nên là ID nội bộ backend, không dùng IP làm khóa chính
- `online` nên được suy ra từ heartbeat gần nhất, không chỉ dựa vào một flag gửi lên
- `ip` có thể là IP thật hoặc tên hiển thị nếu không muốn public IP

---

## 5.2 Player

Player là người chơi đã từng xuất hiện ở một server được theo dõi.

### Trường dữ liệu đề xuất

| Field | Type | Required | Ý nghĩa |
|---|---|---:|---|
| uuid | string | yes | UUID Minecraft ổn định |
| name | string | yes | Username hiện tại |
| serverId | string | yes | Server mà player thuộc về |
| skinUrl | string | no | URL render skin/head |
| firstSeen | datetime | yes | Lần đầu backend thấy player này |
| lastSeen | datetime | yes | Lần gần nhất player được sync |
| isOnline | boolean | no | Trạng thái online gần nhất |

### JSON mẫu

```json
{
  "uuid": "3b8fdfaa-4ab7-4ff2-8e48-b7f6f817f0ab",
  "name": "DungDepChaiVCL",
  "serverId": "srv_8b4f1d",
  "skinUrl": "https://example.com/skins/3b8fdfaa-4ab7-4ff2-8e48-b7f6f817f0ab.png",
  "firstSeen": "2026-04-10T10:00:00Z",
  "lastSeen": "2026-04-17T12:00:00Z",
  "isOnline": true
}
```

### Ghi chú thiết kế
- Dùng `uuid` làm khóa chính logic, không dùng username
- `name` có thể đổi theo thời gian, UUID thì không
- `skinUrl` có thể generate ở backend hoặc web, nhưng field này vẫn hữu ích cho client

---

## 5.3 Party Pokémon

Đây là dữ liệu quan trọng nhất của V1.

Một player có tối đa 6 Pokémon trong party.

### Trường dữ liệu đề xuất cho 1 Pokémon

| Field | Type | Required | Ý nghĩa |
|---|---|---:|---|
| slot | integer | yes | Vị trí 1–6 trong party |
| species | string | yes | Tên species |
| dexNumber | integer | no | National Dex number |
| nickname | string | no | Tên riêng |
| level | integer | yes | Level hiện tại |
| gender | string | no | male / female / genderless |
| nature | string | no | Nature |
| ability | string | no | Ability hiện tại |
| heldItem | string | no | Item đang cầm |
| form | string | no | Form hiện tại |
| shiny | boolean | yes | Có phải shiny không |
| moves | array[string] | yes | 1–4 move |
| ivs | object | yes | IV theo 6 chỉ số |
| evs | object | yes | EV theo 6 chỉ số |
| stats | object | no | Stat hiện tại nếu muốn hiển thị thêm |
| hpCurrent | integer | no | HP hiện tại nếu muốn |
| hpMax | integer | no | HP tối đa |

### JSON mẫu cho 1 Pokémon

```json
{
  "slot": 1,
  "species": "Garchomp",
  "dexNumber": 445,
  "nickname": "Sharky",
  "level": 78,
  "gender": "male",
  "nature": "Jolly",
  "ability": "Rough Skin",
  "heldItem": "Choice Scarf",
  "form": "default",
  "shiny": false,
  "moves": [
    "Earthquake",
    "Dragon Claw",
    "Stone Edge",
    "Fire Fang"
  ],
  "ivs": {
    "hp": 31,
    "atk": 31,
    "def": 20,
    "spa": 12,
    "spd": 26,
    "spe": 31
  },
  "evs": {
    "hp": 0,
    "atk": 252,
    "def": 0,
    "spa": 0,
    "spd": 4,
    "spe": 252
  },
  "stats": {
    "hp": 289,
    "atk": 289,
    "def": 193,
    "spa": 142,
    "spd": 166,
    "spe": 273
  },
  "hpCurrent": 289,
  "hpMax": 289
}
```

### JSON mẫu cho full party

```json
{
  "playerUuid": "3b8fdfaa-4ab7-4ff2-8e48-b7f6f817f0ab",
  "party": [
    {
      "slot": 1,
      "species": "Garchomp",
      "dexNumber": 445,
      "nickname": "Sharky",
      "level": 78,
      "gender": "male",
      "nature": "Jolly",
      "ability": "Rough Skin",
      "heldItem": "Choice Scarf",
      "form": "default",
      "shiny": false,
      "moves": ["Earthquake", "Dragon Claw", "Stone Edge", "Fire Fang"],
      "ivs": { "hp": 31, "atk": 31, "def": 20, "spa": 12, "spd": 26, "spe": 31 },
      "evs": { "hp": 0, "atk": 252, "def": 0, "spa": 0, "spd": 4, "spe": 252 }
    },
    {
      "slot": 2,
      "species": "Rotom-Wash",
      "dexNumber": 479,
      "nickname": null,
      "level": 76,
      "gender": "genderless",
      "nature": "Bold",
      "ability": "Levitate",
      "heldItem": "Leftovers",
      "form": "wash",
      "shiny": false,
      "moves": ["Hydro Pump", "Volt Switch", "Will-O-Wisp", "Pain Split"],
      "ivs": { "hp": 31, "atk": 0, "def": 31, "spa": 31, "spd": 31, "spe": 31 },
      "evs": { "hp": 252, "atk": 0, "def": 252, "spa": 0, "spd": 4, "spe": 0 }
    }
  ]
}
```

### Ghi chú thiết kế
- `moves` nên lưu theo tên hiển thị chuẩn
- `ivs` và `evs` phải cố định 6 key để frontend render ổn định
- `heldItem` nên để `null` nếu không có item, không nên dùng string rỗng
- `form` rất quan trọng về sau nếu m muốn render sprite đúng variant

---

## 5.4 Pokédex Entry

Pokédex dùng để xác định player đã mở species nào.

### Trường dữ liệu đề xuất

| Field | Type | Required | Ý nghĩa |
|---|---|---:|---|
| species | string | yes | Tên species |
| dexNumber | integer | yes | Số dex |
| unlocked | boolean | yes | Đã mở entry chưa |
| caught | boolean | yes | Đã bắt chưa |
| seen | boolean | no | Đã thấy chưa |

### JSON mẫu

```json
{
  "playerUuid": "3b8fdfaa-4ab7-4ff2-8e48-b7f6f817f0ab",
  "entries": [
    {
      "species": "Bulbasaur",
      "dexNumber": 1,
      "unlocked": true,
      "caught": true,
      "seen": true
    },
    {
      "species": "Mewtwo",
      "dexNumber": 150,
      "unlocked": false,
      "caught": false,
      "seen": false
    }
  ]
}
```

### Ghi chú thiết kế
- Nếu V1 chỉ cần locked/unlocked, vẫn nên giữ chỗ cho `seen`
- Web có thể dùng static Pokédex master list rồi merge theo `species` hoặc `dexNumber`
- `caught` và `unlocked` có thể giống nhau ở V1, nhưng tách sẵn sẽ tiện hơn sau này

---

# 6. API contract chi tiết

Phase 1 cần chốt API contract trước khi code backend.

API chia thành 2 nhóm:

1. **Mod → Backend**
2. **Web → Backend**

---

## 6.1 Mod → Backend

### A. Register server

**Purpose**  
Đăng ký server khi server khởi động hoặc khi mod bắt đầu chạy.

**Method**
```http
POST /api/server/register
```

**Headers**
```http
Authorization: Bearer YOUR_API_KEY
Content-Type: application/json
```

**Request body**
```json
{
  "name": "Cobbleverse Alpha",
  "ip": "play.cobbleverse.net",
  "mcVersion": "1.21.1",
  "cobblemonVersion": "1.7.3",
  "modVersion": "1.0.0"
}
```

**Response 200**
```json
{
  "serverId": "srv_8b4f1d",
  "name": "Cobbleverse Alpha",
  "registered": true
}
```

**Validation rules**
- `name` bắt buộc
- `mcVersion` bắt buộc
- `cobblemonVersion` bắt buộc
- `modVersion` bắt buộc

---

### B. Heartbeat

**Purpose**  
Báo cho backend biết server vẫn còn online.

**Method**
```http
POST /api/server/heartbeat
```

**Headers**
```http
Authorization: Bearer YOUR_API_KEY
Content-Type: application/json
```

**Request body**
```json
{
  "serverId": "srv_8b4f1d",
  "onlinePlayerCount": 14
}
```

**Response 200**
```json
{
  "serverId": "srv_8b4f1d",
  "heartbeatAccepted": true,
  "lastSeen": "2026-04-17T12:00:00Z"
}
```

**Validation rules**
- `serverId` bắt buộc
- heartbeat nên gọi mỗi 30–60 giây
- backend có thể tự suy ra `online = true` nếu heartbeat còn mới

---

### C. Upsert player

**Purpose**  
Tạo mới hoặc cập nhật player khi player join hoặc được sync.

**Method**
```http
POST /api/player/upsert
```

**Headers**
```http
Authorization: Bearer YOUR_API_KEY
Content-Type: application/json
```

**Request body**
```json
{
  "uuid": "3b8fdfaa-4ab7-4ff2-8e48-b7f6f817f0ab",
  "name": "DungDepChaiVCL",
  "serverId": "srv_8b4f1d",
  "isOnline": true
}
```

**Response 200**
```json
{
  "uuid": "3b8fdfaa-4ab7-4ff2-8e48-b7f6f817f0ab",
  "upserted": true
}
```

**Validation rules**
- `uuid` bắt buộc
- `name` bắt buộc
- `serverId` bắt buộc

---

### D. Sync party

**Purpose**  
Gửi full party snapshot của player.

**Method**
```http
POST /api/player/sync-party
```

**Headers**
```http
Authorization: Bearer YOUR_API_KEY
Content-Type: application/json
```

**Request body**
```json
{
  "playerUuid": "3b8fdfaa-4ab7-4ff2-8e48-b7f6f817f0ab",
  "party": [
    {
      "slot": 1,
      "species": "Garchomp",
      "dexNumber": 445,
      "nickname": "Sharky",
      "level": 78,
      "gender": "male",
      "nature": "Jolly",
      "ability": "Rough Skin",
      "heldItem": "Choice Scarf",
      "form": "default",
      "shiny": false,
      "moves": ["Earthquake", "Dragon Claw", "Stone Edge", "Fire Fang"],
      "ivs": { "hp": 31, "atk": 31, "def": 20, "spa": 12, "spd": 26, "spe": 31 },
      "evs": { "hp": 0, "atk": 252, "def": 0, "spa": 0, "spd": 4, "spe": 252 }
    }
  ]
}
```

**Response 200**
```json
{
  "playerUuid": "3b8fdfaa-4ab7-4ff2-8e48-b7f6f817f0ab",
  "partySynced": true,
  "slotsReceived": 1
}
```

**Validation rules**
- `playerUuid` bắt buộc
- `party` là array
- mỗi phần tử phải có `slot`
- `slot` chỉ nhận 1–6
- `moves` nên giới hạn tối đa 4
- `ivs` và `evs` phải có đủ 6 chỉ số nếu backend chọn strict mode

---

### E. Sync pokedex

**Purpose**  
Gửi trạng thái Pokédex đã mở của player.

**Method**
```http
POST /api/player/sync-pokedex
```

**Headers**
```http
Authorization: Bearer YOUR_API_KEY
Content-Type: application/json
```

**Request body**
```json
{
  "playerUuid": "3b8fdfaa-4ab7-4ff2-8e48-b7f6f817f0ab",
  "entries": [
    {
      "species": "Bulbasaur",
      "dexNumber": 1,
      "unlocked": true,
      "caught": true,
      "seen": true
    },
    {
      "species": "Mewtwo",
      "dexNumber": 150,
      "unlocked": false,
      "caught": false,
      "seen": false
    }
  ]
}
```

**Response 200**
```json
{
  "playerUuid": "3b8fdfaa-4ab7-4ff2-8e48-b7f6f817f0ab",
  "pokedexSynced": true,
  "entryCount": 2
}
```

**Validation rules**
- `playerUuid` bắt buộc
- `entries` là array
- mỗi entry phải có `species`, `dexNumber`, `unlocked`, `caught`

---

## 6.2 Web → Backend

### A. Get servers

**Purpose**  
Hiển thị danh sách server trên trang `/servers`.

**Method**
```http
GET /api/servers
```

**Response 200**
```json
{
  "servers": [
    {
      "id": "srv_8b4f1d",
      "name": "Cobbleverse Alpha",
      "ip": "play.cobbleverse.net",
      "mcVersion": "1.21.1",
      "cobblemonVersion": "1.7.3",
      "modVersion": "1.0.0",
      "online": true,
      "lastSeen": "2026-04-17T12:00:00Z"
    }
  ]
}
```

---

### B. Get server detail / players

**Method**
```http
GET /api/servers/{serverId}/players
```

**Response 200**
```json
{
  "serverId": "srv_8b4f1d",
  "players": [
    {
      "uuid": "3b8fdfaa-4ab7-4ff2-8e48-b7f6f817f0ab",
      "name": "DungDepChaiVCL",
      "skinUrl": "https://example.com/skins/3b8fdfaa-4ab7-4ff2-8e48-b7f6f817f0ab.png",
      "lastSeen": "2026-04-17T12:00:00Z",
      "isOnline": true
    }
  ]
}
```

---

### C. Get player profile

**Method**
```http
GET /api/players/{uuid}
```

**Response 200**
```json
{
  "uuid": "3b8fdfaa-4ab7-4ff2-8e48-b7f6f817f0ab",
  "name": "DungDepChaiVCL",
  "serverId": "srv_8b4f1d",
  "skinUrl": "https://example.com/skins/3b8fdfaa-4ab7-4ff2-8e48-b7f6f817f0ab.png",
  "firstSeen": "2026-04-10T10:00:00Z",
  "lastSeen": "2026-04-17T12:00:00Z",
  "isOnline": true
}
```

---

### D. Get player party

**Method**
```http
GET /api/players/{uuid}/party
```

**Response 200**
```json
{
  "playerUuid": "3b8fdfaa-4ab7-4ff2-8e48-b7f6f817f0ab",
  "party": [
    {
      "slot": 1,
      "species": "Garchomp",
      "dexNumber": 445,
      "nickname": "Sharky",
      "level": 78,
      "nature": "Jolly",
      "ability": "Rough Skin",
      "heldItem": "Choice Scarf",
      "form": "default",
      "shiny": false,
      "moves": ["Earthquake", "Dragon Claw", "Stone Edge", "Fire Fang"],
      "ivs": { "hp": 31, "atk": 31, "def": 20, "spa": 12, "spd": 26, "spe": 31 },
      "evs": { "hp": 0, "atk": 252, "def": 0, "spa": 0, "spd": 4, "spe": 252 }
    }
  ]
}
```

---

### E. Get player pokedex

**Method**
```http
GET /api/players/{uuid}/pokedex
```

**Response 200**
```json
{
  "playerUuid": "3b8fdfaa-4ab7-4ff2-8e48-b7f6f817f0ab",
  "entries": [
    {
      "species": "Bulbasaur",
      "dexNumber": 1,
      "unlocked": true,
      "caught": true,
      "seen": true
    },
    {
      "species": "Mewtwo",
      "dexNumber": 150,
      "unlocked": false,
      "caught": false,
      "seen": false
    }
  ]
}
```

---

# 7. Database schema đề xuất

V1 chưa cần normalize quá sâu. Mô hình hybrid là hợp lý nhất.

## 7.1 Bảng `servers`

| Column | Type | Note |
|---|---|---|
| id | text PK | ID nội bộ |
| name | text | server name |
| ip | text nullable | ip/display endpoint |
| mc_version | text | minecraft version |
| cobblemon_version | text | cobblemon version |
| mod_version | text | bridge mod version |
| online | boolean | derived or stored |
| last_seen | timestamptz | last heartbeat |
| created_at | timestamptz | created time |
| updated_at | timestamptz | updated time |

---

## 7.2 Bảng `players`

| Column | Type | Note |
|---|---|---|
| uuid | text PK | minecraft uuid |
| name | text | current name |
| server_id | text FK | current / owning server |
| skin_url | text nullable | skin render |
| first_seen | timestamptz | first time seen |
| last_seen | timestamptz | last sync |
| is_online | boolean | last known state |

---

## 7.3 Bảng `player_party`

Mỗi dòng là 1 slot của 1 player.

| Column | Type | Note |
|---|---|---|
| id | bigserial PK | internal row id |
| player_uuid | text FK | owner |
| slot | integer | 1–6 |
| species | text | quick query |
| dex_number | integer nullable | quick query |
| pokemon_data | jsonb | full pokemon payload |
| updated_at | timestamptz | last sync |

### Vì sao có cả cột riêng và JSONB
- `slot`, `species`, `player_uuid` cần query nhanh
- JSONB giữ dữ liệu full và linh hoạt
- sau này muốn thêm field không phải migrate liên tục

---

## 7.4 Bảng `player_pokedex`

| Column | Type | Note |
|---|---|---|
| id | bigserial PK | internal row id |
| player_uuid | text FK | owner |
| species | text | species name |
| dex_number | integer | national dex |
| unlocked | boolean | entry unlocked |
| caught | boolean | caught state |
| seen | boolean | seen state |
| updated_at | timestamptz | last sync |

### Unique constraint nên có
- `(player_uuid, species)` hoặc `(player_uuid, dex_number)`

---

# 8. Quy tắc validation

## 8.1 Validation chung
- Mọi request từ mod phải có API key
- JSON phải parse được
- Các field required không được thiếu
- Nếu request sai shape thì trả `400`

## 8.2 Validation cho server register
- `name`, `mcVersion`, `cobblemonVersion`, `modVersion` là required
- `ip` optional

## 8.3 Validation cho player upsert
- `uuid` phải đúng format UUID hoặc ít nhất là string không rỗng
- `name` không rỗng
- `serverId` phải tồn tại

## 8.4 Validation cho party sync
- `party` phải là array
- `slot` phải nằm trong 1–6
- không nên có 2 Pokémon cùng slot
- `moves.length <= 4`
- `ivs` / `evs` nếu strict thì phải đủ 6 key

## 8.5 Validation cho pokedex sync
- mỗi entry phải có `species` và `dexNumber`
- `unlocked`, `caught`, `seen` là boolean
- không được duplicate species trong cùng payload nếu backend strict

---

# 9. Auth design cho V1

## Cách chọn
**Bearer API key**

### Header mẫu
```http
Authorization: Bearer YOUR_API_KEY
```

## Vì sao chọn cách này
- dễ làm ở Fabric mod
- đủ cho V1
- không phải dựng OAuth hay hệ admin phức tạp

## Gợi ý thực tế
- Mỗi server có 1 API key riêng
- Backend map API key → server
- Có thể cho phép `register` dùng một bootstrap key rồi backend trả `serverId`

---

# 10. Quy tắc sync

Phase 1 cũng nên chốt trước tần suất sync để backend và mod code cùng hướng.

## Đề xuất
- `register`: khi server start
- `heartbeat`: mỗi 30–60 giây
- `player upsert`: khi player join, và có thể gọi lại khi đổi tên nếu cần
- `sync party`: mỗi 30–60 giây cho player online
- `sync pokedex`: mỗi 30–60 giây hoặc khi detect có thay đổi

## Vì sao không sync mỗi tick
- quá nặng
- không cần cho dashboard
- tăng load vô ích cho server + backend + DB

---

# 11. Test payload để mock backend

Trước khi có mod thật, backend phải test được bằng mock JSON.

## 11.1 Register server

```json
{
  "name": "Cobbleverse Alpha",
  "ip": "play.cobbleverse.net",
  "mcVersion": "1.21.1",
  "cobblemonVersion": "1.7.3",
  "modVersion": "1.0.0"
}
```

## 11.2 Upsert player

```json
{
  "uuid": "3b8fdfaa-4ab7-4ff2-8e48-b7f6f817f0ab",
  "name": "DungDepChaiVCL",
  "serverId": "srv_8b4f1d",
  "isOnline": true
}
```

## 11.3 Sync party

```json
{
  "playerUuid": "3b8fdfaa-4ab7-4ff2-8e48-b7f6f817f0ab",
  "party": [
    {
      "slot": 1,
      "species": "Garchomp",
      "dexNumber": 445,
      "nickname": "Sharky",
      "level": 78,
      "gender": "male",
      "nature": "Jolly",
      "ability": "Rough Skin",
      "heldItem": "Choice Scarf",
      "form": "default",
      "shiny": false,
      "moves": ["Earthquake", "Dragon Claw", "Stone Edge", "Fire Fang"],
      "ivs": { "hp": 31, "atk": 31, "def": 20, "spa": 12, "spd": 26, "spe": 31 },
      "evs": { "hp": 0, "atk": 252, "def": 0, "spa": 0, "spd": 4, "spe": 252 }
    }
  ]
}
```

## 11.4 Sync pokedex

```json
{
  "playerUuid": "3b8fdfaa-4ab7-4ff2-8e48-b7f6f817f0ab",
  "entries": [
    { "species": "Bulbasaur", "dexNumber": 1, "unlocked": true, "caught": true, "seen": true },
    { "species": "Mewtwo", "dexNumber": 150, "unlocked": false, "caught": false, "seen": false }
  ]
}
```

---

# 12. Quyết định thiết kế và lý do lựa chọn

## 12.1 Vì sao tách party và pokedex thành endpoint riêng
- payload nhỏ hơn
- update độc lập
- party đổi nhiều hơn pokedex
- dễ retry và debug

## 12.2 Vì sao dùng UUID cho player
- username có thể đổi
- UUID ổn định hơn
- query bền hơn

## 12.3 Vì sao dùng JSONB cho chi tiết Pokémon
- dữ liệu Pokémon nhiều field
- Cobblemon có thể thêm field mới
- giảm số lần migrate DB

## 12.4 Vì sao web không đọc trực tiếp server Minecraft
- frontend không nên chạm dữ liệu game
- không an toàn
- khó scale
- khó debug

## 12.5 Vì sao web nên có static Pokédex master list
- backend chỉ cần trả unlocked list
- giảm payload
- render `?` / sprite dễ hơn
- filter/search tốt hơn

---

# 13. Checklist hoàn thành Phase 1

Phase 1 được xem là xong khi m đã có đủ:

- [ ] Danh sách entity chính
- [ ] JSON mẫu cho server
- [ ] JSON mẫu cho player
- [ ] JSON mẫu cho party
- [ ] JSON mẫu cho pokedex
- [ ] API contract cho mod → backend
- [ ] API contract cho web → backend
- [ ] Database schema draft
- [ ] Validation rules cơ bản
- [ ] Bộ mock payload để test backend

---

# 14. Output mong đợi sau Phase 1

Khi xong phase này, m có thể bắt đầu **Phase 2: build backend**.

Backend phase sau sẽ có:
- FastAPI project structure
- Pydantic schemas
- SQLAlchemy models
- Alembic migration
- POST routes cho mod
- GET routes cho web
- test bằng curl/Postman

---

# 15. Bước tiếp theo

Sau file này, bước đúng là:

**Phase 2 — Build backend**

Thứ tự chuẩn:
1. Tạo Pydantic schemas từ data model ở trên
2. Tạo DB models
3. Tạo migration
4. Tạo POST sync endpoints
5. Tạo GET query endpoints
6. Test bằng mock payload
7. Xong mới chuyển qua viết Fabric mod

---

# 16. Tóm tắt cực ngắn

Phase 1 là bước **định nghĩa chuẩn dữ liệu của toàn hệ thống**.

Nó trả lời 4 câu hỏi quan trọng:
1. Hệ thống lưu những gì?
2. Mod gửi gì?
3. Backend trả gì?
4. Web sẽ nhận shape dữ liệu nào?

Nếu Phase 1 làm chắc, Phase 2 và Phase 3 sẽ dễ hơn rất nhiều.
