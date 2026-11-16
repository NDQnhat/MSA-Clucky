## DEPLOY-02 — Tối ưu build, phát hành và rollback CI/CD cho full-stack Clucky

**Phụ trách:** Chưa phân công (DevOps / Docker + GitHub Actions) · **Sprint:** 4 · **Ước lượng:** 8 SP (14 giờ đề xuất) · **Ưu tiên:** 2/5
**Phụ thuộc:** Release workflow, registry GHCR, môi trường VPS/staging, cấu hình Config Server và secret được owner môi trường cấp; tương thích migration Flyway.

### 1. User Story

- **Là DevOps**, tôi muốn commit lên main được đóng gói một lần thành release truy ngược được source, chỉ build các image thực sự thay đổi, triển khai có health gate và log đủ chẩn đoán để rút ngắn thời gian phát hành.
- **Là người vận hành Clucky**, tôi muốn có thể phát hiện lỗi secret/config/schema/health trước khi đổi release, phục hồi đúng phiên bản còn tương thích mà giữ nguyên dữ liệu PostgreSQL, Redis, Kafka và lịch sử Flyway.
- **Là người review PR**, tôi muốn CI xác nhận test/build phù hợp trước khi đưa lên production và biết rõ artifact, môi trường, dịch vụ bị tác động cũng như cách rollback.

### 2. Công việc

- Rà workflow hiện có `.github/workflows/frontend-ci.yml` (display name **Clucky full-stack CI/CD**), `.github/workflows/firebase-secret.yml`, `deploy/images.py`, `deploy/backend/{prepare.py,deploy.sh,smoke.py}`, `deploy/frontend/deploy.sh`, các compose, `start-docker.ps1` và `deploy/backend/README.md`. Ghi lại luồng PR → main → release plan → build/reuse image → GHCR → deploy backend/frontend → smoke, xác định bottleneck từ dữ liệu build thực.
- Kiểm tra PR gates hiện có: backend tests/migration với PostgreSQL thử nghiệm, frontend lint/unit/build, Playwright và kiểm tra cấu hình deploy; không cấp quyền phát hành production cho pull request. Chỉ thêm gate khi kiểm chứng được lỗi mà gate hiện tại không bắt, tránh test lặp vô ích.
- Chuẩn hóa chọn image trong `deploy/images.py` theo input hash (source tree, build args công khai và cache epoch): thay đổi Customer chỉ package Customer, thay đổi Admin chỉ package Admin, thay đổi service Java chỉ package service đó; thay đổi shared build input/config ảnh hưởng nhiều service phải tái tính dependencies chính xác. **Phân biệt** chỉ build image thay đổi với Compose trên production vẫn reconcile toàn bộ desired state, không hứa production chỉ chạy một lệnh restart duy nhất.
- Giữ release ID dạng `commit SHA + run ID + attempt`, đóng gói `release-plan.json`, `images.json` và image theo **registry digest sha256 bất biến**. Lần chạy lại chỉ được reuse đúng artifact/version; không overwrite tag trỏ sang commit khác hoặc tái sử dụng manifest không khớp với release.
- Tối ưu cache Gradle/npm/Docker Buildx/GitHub Actions theo key độc lập từng service, kiểm invalidation khi lockfile, Dockerfile, base image, env public và source chung thay đổi. Giữ khả năng refresh base image có kiểm soát qua `IMAGE_CACHE_EPOCH`; báo rõ service build/reuse/skipped và lý do.
- Rà thứ tự apply thực tế: PostgreSQL/Redis/Kafka → Config Server + snapshot config-repo cùng release → Eureka → Identity/Catalog/Cart-Order/Payment/Promotion/Shipping/Notification/Review/Audit → Gateway → hai frontend. Dừng sớm khi config/migration/liveness/readiness hoặc Eureka registration không đạt, không đánh dấu healthy chỉ vì container ở trạng thái running.
- Đối chiếu `deploy/backend/deploy.sh` đang có `pg_dump` trước rollout, `flock` và `docker compose up --wait`; `deploy/frontend/deploy.sh` có current/previous symlink và rollback frontend tự động khi lỗi. Viết rõ **rollback backend phải kiểm compatibility schema**: không tự down-migrate Flyway, không tự restore DB nếu chưa có quyết định vận hành; xác định các tình huống cho phép rollback code an toàn.
- Giữ secrets qua GitHub Secrets/Variables và file quyền hạn tối thiểu trên VPS; kiểm đúng `BACKEND_ENV_JSON`/Firebase Service Account, Mapbox public `pk.` so với server token, SMTP, Bridge SMS và SePay. Không dump giá trị env/JSON/token vào log, artifact, cache, image frontend hoặc manifest; job được cấp `contents/packages` đúng mức cần thiết.
- Validation trước deploy: VPS_HOST/PORT/USER/workdir, SSH pinned known_hosts, chính sách CORS/CSRF và `AUTH_COOKIE_SECURE` theo origin, Firebase project trùng frontend/backend, các flag `SEPAY_POS_ENABLED` với key/webhook tương ứng, `MAIL_SENDER_ENABLED` và các mật khẩu/mail sender đúng môi trường. Secret thiếu phải fail closed và nêu **tên biến** thiếu, không in nội dung.
- Xem xét upload bundle/SSH timeout: dùng staging incoming theo `RELEASE_ID`, kiểm SHA256SUMS, refuse manifest sai/cũ, lock triển khai, cleanup credential registry tạm sau mọi trạng thái; retry bước truyền tải an toàn, không apply 2 release cạnh tranh. Không xóa incoming/current/previous hay volumes vô điều kiện khi lỗi.
- Sau deploy chạy container health, Eureka và `deploy/backend/smoke.py` cho public Customer/Admin login, route catalog Gateway, origin hợp lệ/bị chặn; nếu tích hợp cần smoke thanh toán/email thì chỉ dùng hành vi đọc/an toàn và secret môi trường thử nghiệm, không phát giao dịch/người dùng thực. Lưu log có correlation/release-id và kết quả từng gate.
- Trong local phát hành ứng dụng chỉ chạy `./start-docker.ps1 -Services customer` / `admin` / `cart-order-service` (hoặc service bị tác động), script `--no-deps --wait` và `docker compose ps`; phục hồi không dùng `down -v`, `docker volume prune` hay thay DB password đang giữ volume. Không yêu cầu rebuild toàn stack khi chỉ sửa UI.
- Cập nhật runbook với ma trận `thay đổi file → affected image → Config Server dependency → thứ tự restart → health endpoint → rollback path`; ghi lệnh phát hành/rollback thật (`deploy/backend/deploy.sh apply|rollback RELEASE_ID`, `deploy/frontend/deploy.sh apply RELEASE_ID|rollback`), prerequisites và cách lấy release hiện tại/trước đó.
- Bàn giao workflow/script diff, biểu diễn manifest digest, kết quả CI và bằng chứng dry-run/staging, bản ghi backup/health/smoke, runbook xử lý lỗi thường gặp. Tài liệu này là scope task; không coi các mục đã được nghiệm thu chỉ vì pipeline hiện đã có cấu trúc tương ứng.

### 3. API

| Điểm tích hợp / contract | Quyền / yêu cầu |
| --- | --- |
| `.github/workflows/frontend-ci.yml` (đang có, không phải HTTP API) | PR chạy kiểm tra; push/main hoặc dispatch hợp lệ lập release và deploy. Job packaging dùng `packages:write`, job deploy chỉ cần quyền đọc registry và SSH secrets môi trường được duyệt. |
| `python3 deploy/images.py plan/select/args/bundle` (đang có) | Plan source/build args → chọn image cần build → bundle pin digest. `release-plan.json` và `images.json` phải cùng `RELEASE_ID`, SHA và registry digest hợp lệ. |
| `bash deploy/backend/deploy.sh apply RELEASE_ID` (đang có) | Chỉ CI/DevOps được SSH vào VPS; backup Postgres, reconcile infra → Config → Eureka → business → Gateway; giữ volumes và xác minh health. |
| `bash deploy/backend/deploy.sh rollback RELEASE_ID` (đang có) | Người vận hành có quyền; chỉ quay về release tương thích DB/Flyway, phải có backup và quyết định rollback schema/dữ liệu riêng nếu migration không tương thích. |
| `bash deploy/frontend/deploy.sh apply RELEASE_ID` và `rollback` (đang có) | CI/DevOps; xác minh checksum bundle và frontend health, giữ `current`/`previous` để rollback đúng release khi lỗi. |
| `./start-docker.ps1 -Services <service>` (đang có, local) | Operator tại máy dev; validate service từ Compose, chỉ build/up danh sách được nêu, `--no-deps --wait`; ảnh hưởng cấu hình phải kiểm qua `-Action check` và trạng thái qua `-Action status`. |
| `GET /actuator/health` và Eureka registration (đang dùng trong Compose/deploy) | Readiness nội bộ service; không coi HTTP 200 của một service là bằng chứng toàn stack đã đúng nghiệp vụ hoặc đã load config mới. |
| `python3 deploy/backend/smoke.py` (đang có, không phải API mới) | Smoke public URL và Gateway sau rollout; xác minh origin được cấp phép/bị từ chối, không truy cập trực tiếp business service từ frontend. |
| GHCR/SSH/`BACKEND_ENV_JSON`/Firebase secrets (hạ tầng ngoài) | Chỉ principals/jobs có phân quyền tối thiểu; secrets không được đưa vào artifact/public build args. Mapbox frontend `pk.` là public theo thiết kế, còn token riêng tư và các credential còn lại phải được bảo vệ. |

### 4. Tiêu chí hoàn thành

- PR sửa riêng Customer → CI PR gates phù hợp chạy, pipeline production không triển khai PR; khi merge, release plan chỉ build image Customer nếu không có shared dependency thay đổi.
- PR sửa riêng Admin → chỉ image Admin cần build/repackage, backend/Customer được reuse theo digest hợp lệ; không đụng volume.
- Sửa một backend service và migration của chính service đó → image phụ thuộc được chọn chính xác; Config Server/DB readiness được kiểm trước khi Gateway nhận traffic.
- Sửa lockfile, Dockerfile hoặc build arg frontend công khai → hash thay đổi và rebuild service liên quan; không reuse image cũ có runtime config không tương thích.
- Không đổi source hay input, chạy lại workflow với release ID mới → image đầu vào có thể reuse, nhưng manifest/digest gắn đúng SHA; không publish image ghi đè artifact khác commit.
- Tăng `IMAGE_CACHE_EPOCH` có chủ đích → image chịu tác động được refresh, log giải thích thay đổi; lần chạy thường không âm thầm kéo base image ngoài policy.
- Sai `BACKEND_ENV_JSON` hoặc thiếu `VPS_SSH_KNOWN_HOSTS` → preflight/deploy fail rõ tên cấu hình, trước khi switch release, không xuất secrets.
- Firebase service account project khác public project → chặn deployment; không tạo container Identity dùng credentials sai project.
- Secret Mapbox `sk.` bị đưa vào public build args → validator reject; JS bundle và CI artifacts không chứa server token.
- Bật SePay POS/SMTP/Bridge SMS mà thiếu credential bắt buộc → preflight hoặc service fail closed theo contract; không hiện thanh toán/email/SMS thành công giả.
- Config Server không khỏe hoặc Eureka thiếu đăng ký service → rollout dừng, không switch sang trạng thái release healthy; log chỉ rõ service lỗi.
- Flyway migration lỗi giữa deploy → dừng service phụ thuộc; bảo toàn database/backup/volume và báo rõ cần kiểm compatibility trước rollback code.
- Service backend hoặc frontend health thất bại → job non-zero, không báo “deploy success”; rollback frontend trả về `previous` nếu đủ điều kiện, backend có hướng phục hồi được ghi.
- Upload bundle thiếu file hoặc SHA256SUMS sai → từ chối apply release; giữ release cũ đang chạy và log đủ xác định file/step lỗi.
- Hai workflow production đồng thời → lock/concurrency không để cùng lúc chuyển symlink/config hay ghi chồng release, bản cũ còn khả năng chạy.
- SSH disconnect sau khi apply có thể đã thành công → kiểm `current`, release ID và health trước retry; không apply lại mutation/schema/rollback mù.
- Public smoke: Customer/Admin login page, Gateway catalog, CORS hợp lệ và untrusted-origin rejection đúng → kết quả gắn release ID, response code và timestamp.
- Rollback release frontend thất bại → quay về `previous` có digest cũ, health pass; không xóa dữ liệu hay image cần cho rollback.
- Rollback backend khi migration không tương thích → bị chặn theo runbook hoặc yêu cầu recovery có backup/owner rõ ràng; không tự down-migrate Flyway.
- Local `./start-docker.ps1 -Services admin` → build/up/check container Admin và giữ nguyên các service/volumes không liên quan; ghi nhận trạng thái sau script.
- Sau staging/dry-run, bàn giao ma trận service/image/config, runbook backup/rollback, CI job IDs/log đã che secrets và bảng kết quả từng tình huống; nêu rõ tình huống chưa được chạy.

### 5. Điểm cần chốt

- Chốt môi trường staging/VPS, trách nhiệm duyệt production, mức downtime chấp nhận và giới hạn tài nguyên/disk để giữ đủ backup/image current+previous.
- Chốt chính sách rollback backend khi Flyway migration đã chạy: rollback code-only tương thích hay restore DB từ backup sau phê duyệt; RPO/RTO do owner quyết định, không hứa rollback schema tự động.
- Chốt phạm vi change detection khi đổi `config-repo`, base image, shared Gradle/config/build arg; service nào cần recreate/restart dù image binary không đổi.
- Chốt danh sách secret bắt buộc và nguồn quản lý cho Firebase, Mapbox, SMTP, Bridge SMS, SePay, OAuth/CORS; cấm copy secret local sang CI bằng log/chat/artifact.
- Chốt bộ smoke an toàn cho staging và promotion gates: kiểm chỉ health/public endpoints hay cần thử flows đăng nhập/đặt đơn với dữ liệu demo; tránh tạo giao dịch tài chính thật.
- Chốt thời hạn lưu artifact/release/backups và quyền chạy `workflow_dispatch`, deploy SSH, rollback; cleanup không được phá khả năng phục hồi.
