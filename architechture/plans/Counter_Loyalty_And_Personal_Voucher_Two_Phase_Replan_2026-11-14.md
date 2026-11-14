# Clucky — Kế hoạch hợp nhất Loyalty tại quầy và voucher cá nhân (2 phase)

> **Trạng thái:** Đã triển khai các Phase 1/2 trong workspace ngày 14–15/11/2026; kiểm thử tích hợp/CI thuộc quy trình bàn giao PR.
>
> **Nguồn yêu cầu:** Hợp nhất hai kế hoạch Loyalty tại quầy và Voucher cá nhân ngày 14/11/2026, áp dụng các quyết định cuối cùng của chủ dự án. Đây là tài liệu duy nhất để thực hiện hai phần việc. Các ví dụ endpoint, bảng và payload là thiết kế dự kiến, chưa phải API đã chạy.

## Quy tắc nghiệp vụ đã chốt cho cả hai phase

| Chủ đề | Quy tắc cuối cùng |
|---|---|
| Tích điểm khách vãng lai | Tại quầy nhập **SĐT** để tích; không cần tài khoản và không cần OTP. Không tạo ví guest qua email. **Guest chỉ được tích điểm**, chưa có quyền dùng điểm ở quầy. |
| Mã Loyalty riêng của tài khoản | Mỗi Customer/account có **mã Loyalty động riêng** (dạng chữ/số cho Staff nhập) và **QR Loyalty động**. Chỉ tạo khi Customer mở trang Loyalty đã đăng nhập; cả hai xoay vòng **mỗi 30 giây**. Rời trang/đăng xuất thu hồi phiên; chuyển tab trình duyệt không thu hồi ngay để Staff có thể quét QR đang còn hạn. |
| Quyền khi nhập tay | Staff nhập **SĐT/email đã đủ điều kiện tra account hoặc mã Loyalty dạng chữ/số** → **chỉ tích điểm**, không được dùng/trừ điểm. Chỉ biết một định danh không chứng minh người đó đang đứng tại quầy. |
| Quyền khi quét Loyalty QR | Staff **quét QR Loyalty động đang có hiệu lực từ trang Customer** → được tích và, nếu khách muốn, **dùng số điểm tích luỹ đã chọn để trừ hoá đơn**. Không cần OTP tại quầy; backend phải kiểm chứng token QR, phiên đang hoạt động và bằng chứng quét được gắn với đơn. Không có QR hợp lệ thì `pointsUsed = 0` bắt buộc. |
| Khi nào gộp điểm | **Chỉ khi tài khoản thực sự xác thực SĐT bằng OTP** ở bước đăng ký/bind lần đầu: cộng số dư ví PHONE vào ví CUSTOMER một lần, giữ nguyên toàn bộ lịch sử cũ. OTP là điều kiện để *claim điểm*, không phải điều kiện để *chi điểm tại quầy*. |
| Cấu hình trần | `maxEarnPerOrder`, `maxEarnPerDay`, `maxPointBalance` mặc định **0 = không giới hạn**; Admin có thể chỉnh, áp dụng Web và POS. |
| Giá trị làm căn cứ tích điểm | Căn cứ **tiền hàng cuối cùng sau khi đã trừ voucher, ưu đãi và điểm đã dùng**, không tính phần được thanh toán bằng điểm và phí giao hàng. |
| Email/SĐT tài khoản | **Đã xác thực SĐT hoặc email thì khoá vĩnh viễn việc đổi giá trị tương ứng** ở frontend **và backend**. Chưa có/chưa xác thực được thêm và xác thực lần đầu; không được dùng flow đổi thông tin để vượt khoá. |
| Cấu hình voucher | Admin tạo **campaign không nhập mã**, chọn một/nhiều/tất cả chi nhánh; Store Manager giữ quy trình proposal/approval và giới hạn chi nhánh theo RBAC hiện có. |
| Cấp voucher | Mỗi tài khoản nhận **một voucher cá nhân cho mỗi campaign**; mỗi voucher cá nhân **chỉ dùng một lần thành công**, không có cấu hình dùng lặp 2/5 hay 5/5 cho cá nhân. |
| `usage/max_usage` | **Số lượt sử dụng toàn hệ thống của cả campaign**: `usage = số lượt đã sử dụng hợp lệ toàn hệ thống`, `max_usage = giới hạn tổng lượt do Admin đặt`. Thanh progress của chương trình lấy từ hai số này, **không lấy từ instance cá nhân**. |
| Quyền dùng mã tại POS | **Đúng mã voucher cá nhân là dùng được**; Staff quét QR hoặc nhập mã thủ công, không OTP và không cần xác minh SĐT/email/tài khoản chủ voucher trước khi dùng. Mã là chứng từ mang quyền sử dụng (bearer credential). |
| Voucher trên Web | Customer đã đăng nhập **chọn từ “Voucher của tôi”**; Web không có ô nhập mã voucher. Backend chỉ nhận instance thuộc account đang đăng nhập. |
| Mã voucher | Sinh bằng CSPRNG, độ ngẫu nhiên cao và đủ dài; có **salt riêng cho từng mã** và khoá bí mật phía server để bảo vệ bản lưu/đối chiếu. Không tạo mã dễ đoán, không lưu mã thô vào log. |
| Hủy và refund | **CANCELLED hợp lệ: trả lại voucher để dùng lần nữa** và hoàn quota campaign tương ứng; **REFUND sau đơn hoàn tất: không trả lại voucher** và không hoàn quota campaign. Mọi thay đổi có audit và idempotency. |
| Vòng đời đơn | Reserve lúc tạo đơn hợp lệ; chỉ ghi đã dùng/tích điểm khi đơn thật sự `COMPLETED` và tiền `PAID/COLLECTED`. Preview, scan, nhập mã và đơn chưa hoàn thành không ghi lượt dùng. |

### Những cách hiểu cũ đã loại bỏ

- **Thay đổi so với kế hoạch hợp nhất trước:** bỏ quyền dùng điểm chỉ bằng cách đọc/nhập SĐT, email hay mã chữ. **Chỉ QR Loyalty động đang hoạt động mới cho phép trừ điểm**; guest không có tài khoản vẫn tích được nhưng chưa được dùng điểm. Không yêu cầu OTP tại quầy; thay cho OTP là bằng chứng QR một lần.
- Mã/QR Loyalty **chỉ tồn tại trong phiên trang Loyalty đang mở**, tự thay mỗi 30 giây và hết hiệu lực khi rời trang/đăng xuất; đổi tab không thu hồi ngay mã còn thời hạn, tránh lỗi quét tại POS.
- Bỏ `instance.usage/maxUsage` theo từng khách và `defaultMaxUsage > 1`; một instance chỉ có thể `ISSUED/RESERVED/REDEEMED` (kèm trạng thái hết hạn/thu hồi). Không có “voucher cá nhân còn 4/5 lượt”.
- Bỏ kiểm tra khớp `customerId` của voucher khi **Staff áp mã tại POS** và bỏ yêu cầu OTP xác nhận chủ mã; possession of code là đủ. Chỉ **Web** kiểm tra owner dựa trên JWT.
- `usage/max_usage` trên card thuộc **campaign**. Hai người có hai mã khác nhau nhưng cùng thấy bộ đếm chương trình. Không hiểu `max_usage` là số voucher phát ra.
- Bỏ phương án hoàn voucher sau refund. Giữ đường hoàn **khi hủy đơn**.

---

## Phase 1 — Loyalty QR 30 giây, tích/đổi điểm tại quầy, gộp ví SĐT và khoá contact đã xác thực

**Mục tiêu:** Mỗi account có mã Loyalty và QR riêng chỉ hoạt động khi Customer mở trang Loyalty, đổi mới mỗi 30 giây; Staff nhập mã/SĐT/email chỉ để tích, còn **quét QR hợp lệ mới được dùng điểm**. Guest vẫn tích điểm qua SĐT và có thể claim sau khi xác thực; số dư và lịch sử không mất, contact verified được khoá.

### 1.1. Hành vi và màn hình

1. Trong `frontend/nextjs/app/loyalty/page.tsx`, khi Customer đăng nhập **và thực sự mở trang Loyalty**, gọi server mở phiên cấp mã; hiển thị **mã Loyalty dạng chữ/số**, **QR Loyalty** và đồng hồ đếm ngược **30 giây**. Tại mốc 30 giây, server cấp mã + QR mới, mã/QR cũ hết hiệu lực. Không sinh mã khi login, khi nền trang khác mở, hoặc khi user chưa vào Loyalty.
2. Trong `frontend/react/src/pages/Staff/components/CounterOrderModal.tsx`, chia rõ **Nhập để tích điểm** (SĐT/email account verified hoặc mã Loyalty gõ tay) và **Quét QR Loyalty** (mở modal camera riêng). Không có nút `Không tích điểm`: để trống thông tin Loyalty mặc định không tích. Không tự lấy `receiverPhone` của đơn để tích.
3. Nhập SĐT guest/khách có account hoặc **mã Loyalty dạng chữ/số** chỉ resolve ví và cho thao tác **Tích điểm**. Nút `Dùng điểm trừ phí` phải bị ẩn/disabled; backend ép `pointsUsed=0` ở cả preview và create Order. Không lấy tên/số dư đầy đủ, không cho phép nhập tuỳ ý QR payload vào ô mã chữ để nâng quyền.
4. **Quét QR Loyalty động hợp lệ:** backend nhận QR token, xác minh chữ ký/bí mật, Customer session và hạn 30 giây, tạo `scanProof` chỉ một lần, gắn Staff, `storeId`, Customer wallet và `draftHash/quote` (giỏ/quán/khách, chưa chốt số điểm sẽ chọn). Sau khi scan thành công, POS mới mở **Dùng điểm trừ phí**, nhập số điểm nguyên hoặc `Dùng tối đa`; Staff có thể chỉ tích nếu khách không muốn đổi điểm. Không có OTP tại POS.
5. Khi Staff đổi giỏ, cửa hàng hoặc khách, phải yêu cầu quote và `scanProof` mới. **Thay đổi số điểm** trong giới hạn đã được QR cấp quyền chỉ cần tính lại quote, không quét lại nếu proof còn hiệu lực. Mã QR gốc hết hạn sau 30 giây không thể quét lại; proof đã cấp khi quét hợp lệ có TTL ngắn riêng và phải bị huỷ nếu phiên Customer đóng trước reserve. Server luôn chặn `pointsUsed>0` nếu chỉ có typed code, SĐT hay email dù frontend cũ cố gửi request.
6. Backend resolve SĐT theo chuẩn E.164 Việt Nam: phone thuộc account đã verify → ví CUSTOMER; phone chưa thuộc account verify → ví PHONE guest. Email chỉ tra account có email verified; không tạo guest wallet qua email. Mã Loyalty động chỉ cấp cho Customer có account JWT hợp lệ.
7. Staff preview giá/điểm từ server, thanh toán số tiền đã tính lại. Sau `COMPLETED + PAID/COLLECTED` ghi EARN/REDEEM một lần; cancel trước settle giải phóng hold. Hóa đơn/chi tiết đơn hiển thị điểm đã dùng, tiền được trừ và điểm đã nhận.
8. Trang `/loyalty` vẫn hiển thị số dư, đang giữ, khả dụng, lịch sử Web/quầy và trạng thái claim; QR/mã có phần hiển thị riêng, chỉ hiện khi đang ở trang. `/account` hiển thị trạng thái verify và kết quả gộp điểm.

**Ranh giới quyền:** chỉ biết SĐT/email hoặc mã gõ tay **không cho quyền tiêu điểm**. QR sống 30 giây là bằng chứng Customer có phiên Loyalty đang mở, nhưng QR có thể bị chụp/chia sẻ: cần giới hạn thời gian, ràng buộc đơn và dùng một lần. Server **không thể chứng minh tuyệt đối camera thật đã đọc QR** nếu kẻ tấn công có nguyên payload; chỉ phân biệt chắc chắn **mã chữ earn-only** với **QR token có bí mật**. Không quảng cáo đây là xác thực danh tính mạnh ngang xác nhận trên điện thoại. Theo yêu cầu, không thêm OTP vào POS.

### 1.1a. Kiến trúc mã Loyalty 30 giây — chỉ tồn tại khi trang mở

**Hai biểu diễn cho cùng một account, hai phạm vi quyền:**

| Trường hiển thị trên trang Customer | Bản chất | Staff dùng bằng | Phạm vi |
|---|---|---|---|
| **Mã Loyalty nhập tay** (ví dụ `LY-8FK2-7M9Q`, ví dụ định dạng) | Chuỗi ngẫu nhiên của *phiên và chu kỳ 30 giây*, chỉ để tìm ví account | Ô nhập mã Loyalty | **EARN only**; tuyệt đối không có quyền REDEEM |
| **QR Loyalty động** | Payload có phiên bản `CLUCKY:LOYALTY:V1:<opaqueToken>`, token ngẫu nhiên mạnh do server phát cho cùng account/chu kỳ; **không đơn thuần encode chuỗi mã nhập tay** | Camera quét Loyalty QR | **EARN hoặc REDEEM** sau khi server validate token và cấp scan proof |
| **SĐT/email** | Contact tra ví (SĐT guest hoặc contact đã verified của account) | Ô nhập contact | **EARN only**; không phải xác minh chủ ví |

1. Customer truy cập `/loyalty` bằng JWT của chính account → frontend phát `open-session`; backend khởi tạo phiên hiện diện ngắn hạn `sessionId/accountId/deviceSessionId/createdAt/leaseExpiresAt/state`. Không sinh trước mã trong DB profile, JWT, đăng nhập, trang Account hay tác vụ nền.
2. Backend tạo **một cặp mã chữ + QR token không trùng với phiên account khác**, random CSPRNG, đều liên kết `accountId + sessionId + generation + expiresAt`. Mã chữ gắn phạm vi `EARN_ONLY`, QR payload gắn phạm vi `EARN_REDEEM`; verifier server phân biệt **theo loại token/bí mật**, không dựa vào trường `inputMethod=SCAN` mà Staff có thể tự khai. QR token dài (ví dụ >=128 bit entropy), mã chữ đủ khó đoán trong 30 giây và bị rate-limit chặt.
3. **Vòng đời mỗi chu kỳ:** phiên đang hoạt động → server tạo cặp token (`issuedAt`, `expiresAt = issuedAt + 30s`); frontend hiển thị đồng hồ dựa trên server time và yêu cầu cặp mới khi hết 30 giây. **Mã cũ hết hạn chính xác khi đến `expiresAt`, không chấp nhận kỳ cũ/grace window**. Reconnect không được làm token cũ sống lại. Quét đúng lúc đổi mã có lỗi “Mã hết hạn, vui lòng quét mã mới”. Nếu mã chữ được nhập nhưng chưa tạo order, cần xác minh lại độ hiệu lực của mã tại bước tạo order; mã hết hạn không còn dùng để gắn ví bằng code.
4. Chỉ cấp mã khi **Customer đang mở trang Loyalty**. Khi chuyển tab trình duyệt, UI che QR nhưng phiên vẫn tiếp tục heartbeat/rotate, để Staff quét được QR vừa nhìn thấy nếu chưa hết 30 giây. Khi rời route, back, logout, unmount, đổi account thì thu hồi phiên. Không giữ token trong localStorage/sessionStorage/URL hoặc push notification.
5. **Không thể bảo đảm thu hồi tức thì khi browser tắt đột ngột/mất mạng**: sự kiện đóng trang không bảo đảm được gửi. Backend kiểm tra **presence heartbeat** (3 giây/lần) và lease dự phòng 70 giây để chịu được throttling của tab nền. Dù lease dài hơn, **mỗi QR/mã chữ chỉ hợp lệ 30 giây và không được tái dùng**, proof quét cũng có TTL riêng 30 giây. Frontend gửi revoke khi rời trang/đăng xuất; session hết lease sẽ bị vô hiệu. Server không thể tự biết chắc trang đang hiển thị chỉ qua API.
6. Server kiểm tra `session ACTIVE`, JWT customer ở các request mở/heartbeat/đóng, `generation`, thời gian server, hash/verifier của token và việc token chưa bị consume; lưu state tạm trong cache TTL hỗ trợ nhiều instance backend (Redis hoặc kho ngắn hạn phù hợp hạ tầng thực tế), **không lưu mã plaintext trong log hay DB vĩnh viễn**. Có thể chọn một phiên hoạt động cho mỗi account; mở tab/thiết bị khác thu hồi phiên trước, UI cũ hiển thị “Mã đã được mở ở thiết bị khác”.
7. Staff quét QR **trước khi QR hết hạn** → Order gọi internal validator → server cấp `scanProof` ngắn hạn (đề xuất tối đa **30 giây từ khi quét**), chỉ dùng **một lần**, bind `accountId/walletId/staffId/storeId/draftHash/amountScope/sessionId/generation/expiry`. `draftHash` giữ giỏ/quán/customer, **không bao gồm điểm Staff sẽ chọn sau quét**; `amountScope` giới hạn số điểm được dùng. Khi Staff đổi `pointsUsed` trong phạm vi hợp lệ, quote cập nhật mà không làm mất proof. `scanProof` mất hiệu lực khi hết TTL hoặc session đóng trước khi reserve. **Tại preview không debit điểm.** Create order phải consume proof trong bước reserve (hoặc giao thức idempotency an toàn), lock ví và check điểm đủ. Khi Order đã reserve thành công, việc Customer rời trang sau đó không làm hỏng settle của order đã hợp lệ.
8. Staff nhập mã chữ đúng nhưng chưa hết hạn chỉ được resolve wallet để **EARN**; không có API đổi mã chữ thành `scanProof`. API dùng điểm yêu cầu bằng chứng từ QR token hợp lệ (không nhận mã chữ, SĐT hoặc email) và quyền Staff tại đúng store. Không có QR → backend từ chối bất kỳ `pointsUsed > 0`, kể cả caller giả mạo cờ `scanned=true`.
9. Tránh leak account: Staff chỉ thấy số tài khoản/định danh đã che và điểm dự kiến được cộng khi nhập; chỉ hiện **điểm có thể dùng** và nút REDEEM khi QR scan proof hợp lệ. Rate-limit theo actor/store/IP/mã và audit scan/hold/settle; token QR và mã gõ tay không được xuất hiện trong analytics hay log. Mã QR Loyalty không dùng để nhận Voucher và ngược lại.

**Giới hạn cần thể hiện đúng trong UI:** quét QR chứng minh Staff đang nắm một token vừa được mở trên phiên Customer, **không thể tự chứng minh Customer đứng cạnh quầy hoặc camera thực sự đã quét thay vì được gửi ảnh QR**. Đây là thiết kế quyền theo possession và TTL 30 giây, phù hợp yêu cầu không OTP; nếu sau này cần xác minh chính chủ mạnh hơn thì phải thêm bước xác nhận phía điện thoại theo quyết định sản phẩm mới.

### 1.2. Giá cuối cùng, trần tích điểm, tiền và vòng đời

```text
subtotal          = tổng giá món/topping từ Catalog xác thực
voucherDiscount   = giảm voucher hợp lệ (khi Phase 2 được bật, nếu có)
tierDiscount      = ưu đãi hạng theo chính sách hiện có (nếu có)
beforePoints      = max(0, subtotal - voucherDiscount - tierDiscount)
pointsDiscount    = min(pointsUsed * pointValue, beforePoints)
merchandisePaid   = max(0, beforePoints - pointsDiscount)
shippingFee       = 0 ở quầy, phí giao hàng nếu là đơn Web
totalToCollect    = merchandisePaid + shippingFee
earnBase          = merchandisePaid       # tổng hàng sau trừ điểm/ưu đãi, không tính ship
rawEarn           = floor(earnBase / spendPerPoint)
pointsToEarn      = applyEarnCaps(rawEarn, activeWallet, pendingEarn)
```

- Giá trị điểm/tỷ lệ hiện hành lấy từ `loyalty_policy` có version; không dùng số frontend tự khai. Không dùng floating point để lưu tiền VND; áp quy tắc làm tròn và khống chế `pointsDiscount <= beforePoints`.
- `pointsUsed` là số **điểm nguyên không âm** Staff chọn; với điểm > 0 phải đủ available balance và không trừ quá tiền hàng. Không âm thầm thay `pointsUsed` khi quỹ không đủ; trả quote yêu cầu xác nhận. Nếu tổng thu 0 VND, dùng trạng thái thanh toán hợp lệ riêng, không sinh lệnh chuyển khoản 0 đồng.
- **Điều kiện `pointsUsed > 0` ở đơn quầy:** Order phải có `loyaltyScanProof` **còn hiệu lực**, được cấp từ QR Loyalty động đã quét khi còn hạn và gắn đúng wallet/store/draft; tra bằng SĐT/email, mã Loyalty gõ tay hoặc ví guest phải luôn có `pointsUsed=0`. Mất/hết hạn **scanProof** hoặc session đóng trước khi reserve thì phải quét mã mới; QR gốc hết 30 giây sau khi scan không tự làm mất proof đã cấp nếu proof và session vẫn hợp lệ. Sau reserve, settlement theo order không phụ thuộc trang còn mở.
- Cùng một order có thể vừa dùng điểm vừa tích điểm trên **phần tiền hàng thực trả**. Web và counter checkout dùng cùng thuật toán; hiện trạng cũ cần đối chiếu trước khi sửa.
- Mở rộng `loyalty_policy` bằng `max_earn_per_order`, `max_earn_per_day`, `max_point_balance` (nonnegative, **default 0/unlimited**). Trần ngày theo `Asia/Ho_Chi_Minh`, phạm vi toàn hệ thống theo ví. Quota phải tính cả phần EARN đang reserve để hai đơn đồng thời không vượt trần.
- Khi điểm guest được claim, không cắt số dư cũ dù vượt `max_point_balance`; cap chỉ giới hạn các lần tích tiếp theo. Chuyển cả quota ngày đang giữ sang ví nhận.
- Với hoàn tiền, ghi bút toán bù điểm theo phần tiền thực hoàn và bản ghi refundId có idempotency, bảo toàn ledger; quy tắc tính EARN giảm theo số tiền đã hoàn và REDEEM hoàn theo số điểm thực tiêu, không cho âm số dư khi schema chưa hỗ trợ (theo policy hoàn tiền hiện hành). **Quy tắc voucher refund ở Phase 2 là độc lập: không trả voucher.**

### 1.3. Mô hình ví, lịch sử và transaction

- Trong Promotion/Loyalty, mở rộng `member_points` thành ví `owner_type=CUSTOMER|PHONE`, `wallet_state=ACTIVE|MERGED`, `merged_into_wallet_id`; giữ nguyên `id` ví Customer hiện tại. Ví PHONE có `customer_id=NULL`, `phone_key=HMAC-SHA256(secret, canonicalE164)` + `phone_key_version`, unique; không dùng SHA256 thường để băm SĐT. Không ghi SĐT thô vào ledger/event.
- Backfill `point_transactions.wallet_id`, `origin_wallet_id`, `source_type/source_ref`; giữ `order_id`, timestamp, `operation_key` và lịch sử giao dịch gốc. `loyalty_orders` lưu wallet ref, reserve và EARN đang chờ. Nâng các trường số điểm dễ tràn từ INT sang BIGINT/long và kiểm tra phía JS.
- Bổ sung `loyalty_phone_claims(source_wallet_id UNIQUE, target_wallet_id, verified_customer_id, verification_event_id UNIQUE, amount, created_at)` và `loyalty_daily_earn_usage(wallet_id, local_date, earned, held)`. Cùng schema Promotion mới có thể FK nội bộ; không FK xuyên service.
- **Claim:** khi Identity xác thực SĐT thành công, Promotion khóa hai ví theo thứ tự ID ổn định, kiểm tra event/owner/binding version, chuyển đúng toàn bộ balance + reserved + lifetime/quota guest sang CUSTOMER, ghi cặp `TRANSFER_OUT/TRANSFER_IN`, đánh dấu PHONE MERGED, ghi claim duy nhất. **Không tạo EARN mới từ khoản chuyển**.
- **Ví dụ bắt buộc:** account có 80 điểm, PHONE guest có ba đơn tích 12 + 7 + 16 = 35 → sau xác thực có **115 điểm**, lịch sử vẫn có đủ ba đơn ban đầu và các đơn của account; nhận cùng event 10 lần vẫn 115.
- Order guest tạo trước khi verify nhưng settle sau khi đã merge phải resolve `merged_into_wallet_id` rồi ghi vào CUSTOMER, đồng thời giữ trace origin PHONE. Reserve/settle, claim và daily quota dùng row-lock, idempotency key, eventId; không để wallet âm, tính trùng, hoặc sinh lại ví cho số đã claim.

### 1.4. Identity: chỉ claim sau xác thực, khoá contact verified

1. Chỉ phát event `identity.customer-phone-verified.v1` **sau khi** OTP xác thực SĐT thành công và transaction Identity commit, thông qua transactional outbox + retries/reconciliation. Thêm `phone_binding_version` và chỉ xử lý binding hiện hành. Không phát event khi Staff nhập số, gửi OTP, hay khi profile chỉ lưu số chưa verify.
2. `REGISTER` hoặc tài khoản Google chưa có SĐT: được **thêm SĐT và xác thực lần đầu**. Nếu tài khoản chỉ có SĐT/email **chưa xác thực**, có thể sửa thông tin chưa verify theo quy trình xác thực hiện có. Sau khi `phone_verified=true`: **mọi endpoint update/rebind/change phone phải chặn**, kể cả endpoint legacy, internal path dùng bởi UI hay cập nhật profile thông thường.
3. Email tương tự: sau `email_verified=true` **không cho đổi email** từ `/account` hoặc bất kỳ mutation profile tương đương; luồng request/verify email chỉ phục vụ gắn/xác thực lần đầu khi email chưa verify. Không cho login Google/đồng bộ provider vô tình ghi đè email đã khoá.
4. Frontend `/account` hiển thị email/SĐT verified dạng chỉ đọc, nhãn **Đã xác thực — không thể thay đổi**, ẩn/disable form/nút thay đổi; backend trả lỗi nghiệp vụ chuẩn nếu frontend cũ cố gửi mutation. Xử lý an toàn trạng thái chưa có contact, chưa verify và verified hiện hữu.
5. Đã xác thực phone để claim → điểm guest gộp vào account. Không cho claim lại vào account khác dù có sự kiện trễ hoặc số điện thoại cũ được tái sử dụng. Trường hợp phone trùng/claim conflict cần audit, xử lý có kiểm soát; không tự chuyển số dư đã claim.
6. Identity giữ nguyên yêu cầu OTP trong **đăng ký/xác thực liên hệ** và các flow bảo mật khác hiện có. **POS không yêu cầu OTP**, nhưng quyền **REDEEM chỉ được mở bằng QR Loyalty động**; không áp dụng cho tra bằng SĐT, email hay mã gõ tay.

### 1.5. Contract dự kiến qua Gateway

| Đường API | Quyền | Hành vi |
|---|---|---|
| `POST /api/loyalty/live-session/open` | CUSTOMER | Frontend chỉ gọi khi trang Loyalty visible; mở phiên tạm cho account từ JWT, trả `sessionId, loyaltyTextCode, loyaltyQrToken, issuedAt, expiresAt, serverTime` |
| `POST /api/loyalty/live-session/heartbeat` | CUSTOMER | Gia hạn presence lease phiên **khi trang còn visible**, có session JWT + sessionId; **không** kéo dài tuổi thọ mã 30 giây |
| `POST /api/loyalty/live-session/rotate` | CUSTOMER | Cấp cặp code + QR mới khi hết chu kỳ, phiên vẫn active; chỉ một mã active mỗi account/chu kỳ |
| `POST /api/loyalty/live-session/close` | CUSTOMER | Revoke phiên, các mã cũ và scan proof chưa consume; best-effort client, backend còn lease timeout |
| `POST /api/orders/counter/loyalty/lookup` | STAFF / STORE_MANAGER / ADMIN (đúng store) | `{kind: PHONE|EMAIL|LOYALTY_TEXT_CODE, value}` → resolve ví **EARN_ONLY**, masked data, không lộ điểm tiêu được |
| `POST /api/orders/counter/loyalty/scan` | Staff role | `{qrToken, draftHash}` từ quét **Loyalty QR** → kiểm tra active session, generation, TTL 30 giây và store; trả proof `EARN_REDEEM` với expiry ngắn |
| `POST /api/orders/counter/preview` | Staff role | `{items,loyaltySubject?,loyaltyScanProof?,pointsUsed,voucherInstanceId?,quoteVersion?}` → giá/điểm server; **`pointsUsed>0` chỉ khi proof QR valid** |
| `POST /api/orders/counter` | Staff role | Tạo order `Idempotency-Key`, consume `loyaltyScanProof` trong quá trình reserve đúng wallet/số điểm/draft; **không có proof thì bắt buộc `pointsUsed=0`** |
| `GET /api/loyalty/me`, `GET /api/loyalty/history` | CUSTOMER | Balance, held, available, timeline gộp và claim status |
| `GET /api/loyalty/claim-status` | CUSTOMER | `PENDING/COMPLETED/RETRYING/CONFLICT` với sự kiện binding |
| `GET/PUT /api/loyalty/config` | ADMIN | Chính sách điểm có 3 trần default 0, có version/audit |
| Identity request/verify contact hiện có | Authenticated account | Chỉ thêm/xác thực liên hệ chưa verified, khoá server-side khi đã verified |
| Internal Identity ↔ Promotion ↔ Order | INTERNAL_SERVICE | Resolve contact verified, outbox, claim, quote/reserve/settle/release, reconciliation snapshot |

**Dữ liệu Order:** `loyalty_wallet_id`, `loyalty_subject_type`, `loyalty_policy_version`, `loyalty_opt_in`, `loyalty_auth_method=CONTACT|TEXT_CODE|QR_SCAN`, `loyalty_scan_proof_id?` (chỉ metadata/id đã dùng, không token), `points_used`, `points_amount`, `points_to_earn`, `total_amount`. Backend ép **`points_used=0` nếu auth method không phải QR_SCAN kèm proof**. Contact để tra cứu chỉ đi qua luồng server cần thiết; event chứa `walletRef/orderId` thay vì số thô. Payment, Staff và Customer dùng **cùng** amount từ Order; không đọc giá tiền do client quyết định.

**Phân chia trách nhiệm:** Identity xác thực JWT Customer; Promotion/Loyalty quản lý phiên sống, mã EARN_ONLY/QR, ví, proof, hạn mức và sổ cái; Order quyết định tiền và chỉ gọi internal Promotion bằng service credentials. Quá trình check token, cấp/consume proof, reserve điểm phải atomic và idempotent tại Promotion; không tin `loyalty_auth_method` hoặc `scan=true` do frontend tự cung cấp nếu không có bằng chứng từ backend. Routes mới phải qua Gateway.

### 1.6. Triển khai và nghiệm thu Phase 1

| Hạng mục | Phạm vi dự kiến |
|---|---|
| DB + backend | Flyway mới cho Promotion wallet/ledger/claim/caps, Identity outbox/binding, Order wallet refs; cache phiên Loyalty động có TTL (không lưu mã vĩnh viễn), scan proof gắn một order, DTO & services `identity-service`, `promotion-loyalty-service`, `cart-order-service`; Payment khi thật sự cần |
| Staff UI | `CounterOrderModal` có **nhập mã/SĐT/email để EARN** và **quét QR Loyalty để có quyền REDEEM**; camera thật, thông báo mã hết hạn/quét lại, payment/receipt/order detail; không OTP; i18n vi/en |
| Customer UI | `/loyalty` có mã chữ + QR đổi 30 giây, countdown, thông báo đã ẩn/thu hồi, dừng lifecycle khi thoát trang; `/account` và profile API khoá edit contact verified |
| Chuyển đổi | Backup/snapshot số dư/ledger, mở rộng schema trước; backfill account hiện hữu, bật feature flag guest EARN và QR REDEEM, **ngừng mọi POS path REDEEM bằng contact/code gõ tay**, giữ API/order cũ để đọc/settle nhưng không cho tạo giao dịch trái rule mới, đối soát event/reconciliation; rollback app/flags mà không xoá dữ liệu điểm |

**Tiêu chí nghiệm thu khi triển khai:**

- Guest chỉ đọc SĐT, tích điểm rồi completed → có EARN một lần; **không thể dùng/trừ 20/35 điểm bằng SĐT**. Sau đăng ký và claim, Customer có QR Loyalty riêng để dùng điểm theo rule mới.
- Account có email/phone verified **bị chặn REDEEM** khi Staff chỉ nhập contact; Staff nhập **mã Loyalty dạng chữ** dù đúng, còn hạn cũng **chỉ được EARN**. API preview và create với `pointsUsed>0` phải trả lỗi quyền REDEEM, không reserve/debit điểm.
- Customer mở `/loyalty` lần đầu → server sinh **mã riêng và QR riêng**, countdown 30 giây; đúng 30 giây cũ mất hiệu lực, mã/QR mới được sinh; hai account không bao giờ cùng active code. Không mở trang thì không có code/token hợp lệ.
- Chuyển tab → UI tạm ẩn QR, phiên Loyalty chưa bị thu hồi; QR chỉ scan được trong 30 giây của chính nó. Chuyển route, logout hoặc tắt app → UI xoá mã, server thu hồi (hoặc đợi lease timeout khi mất kết nối); token cũ không tái sử dụng.
- Staff **quét QR Loyalty đang active** → mở được `Dùng điểm`, dùng 20/35 điểm nếu đủ; server reserve số điểm và tính tiền đúng. Không OTP. QR hết hạn **trước khi quét** hoặc `scanProof` hết hạn/bị revoke **trước reserve** → hướng dẫn quét lại, không tự hạ cấp về quyền nhập tay và không cho dùng điểm.
- Hai quầy cùng quét/đổi trên một ví chỉ đủ một giao dịch → một reserve hợp lệ, một 409; cùng QR/proof hoặc event replay không được chi hai lần. Chỉ quét QR để EARN vẫn hoạt động khi khách không muốn REDEEM.
- Kẻ gửi `scanned=true` cùng mã chữ, gọi thẳng scan endpoint bằng mã chữ, dùng QR đã revoke, dùng proof cho giỏ/quán khác hoặc OTP cũ → bị chặn; không lộ token, tài khoản và số dư ở log/lookup.
- Giỏ 200.000đ, voucher 20.000đ, dùng 30.000đ điểm → giá trị tích điểm là **150.000đ** (chưa tính ship); Web và POS cho cùng kết quả. Khi `totalToCollect=0` không gọi QR chuyển khoản 0 đồng.
- Admin lưu ba cap `0` → không giới hạn; cap >0 hạn chế cả Web và quầy; claim guest không làm mất điểm hay reset cap ngày.
- Phone guest 35 + account 80 → 115 điểm, hiển thị lịch sử gốc; verify event lặp, settle trước/sau merge không tạo thêm điểm.
- Phone/email đã verified: form chỉnh sửa bị khóa và gửi trực tiếp API update vẫn bị chặn; contact chưa verified có thể hoàn tất xác thực lần đầu.
- Cancel order đang reserve → hoàn held points/quota; completed + payment thật → settle một lần; Kafka replay, dịch vụ tạm unavailable được reconciliation cứu.

---

## Phase 2 — Voucher cá nhân một lần, hạn mức toàn chương trình, QR/mã và checkout Web/POS

**Mục tiêu:** Admin tạo chương trình không nhập mã; mỗi Customer nhận mã cá nhân khó đoán để dùng đúng một lần; Staff chỉ cần QR/mã để áp dụng; Web chọn voucher đã nhận; thanh tiến độ phản ánh tổng số lượt dùng toàn hệ thống.

**Ranh giới với Phase 1:** Voucher là mã cá nhân có hiệu lực theo campaign/order, **không đổi mỗi 30 giây**. Đúng **mã Voucher** gõ tay hoặc quét QR Voucher đều có thể giảm giá voucher theo rule Phase 2; điều này **không cấp quyền dùng điểm Loyalty**. Chỉ **QR Loyalty động đang hoạt động** mới mở quyền dùng điểm của ví. Nếu đơn có cả voucher và Loyalty, hai bằng chứng tách biệt và số tiền vẫn tính qua cùng Order quote.

### 2.1. Campaign, phạm vi chi nhánh và global usage

1. Campaign có `campaignId/title/description/type/value/minOrderAmount/maxDiscount/startAt/endAt/status/applicability/storeIds/maxUsage`; **không có code chung để Admin nhập**. `applicability=ALL` hoặc `SELECTED` (ít nhất một chi nhánh); validate store qua Catalog. Store Manager chỉ đề xuất theo chi nhánh được phân quyền; Admin duyệt/không duyệt qua workflow hiện hành.
2. `maxUsage` là **giới hạn lượt dùng thành công của toàn campaign trên mọi cửa hàng và khách hàng**. `usage` là số lượt đã settle hợp lệ, `reservedCount` là lượt đang giữ cho order chưa hoàn tất. Có thể đặt `maxUsage=NULL` để không giới hạn nếu form hiện hành cho phép; UI ghi “Không giới hạn” thay vì thanh progress chia 0.
3. Điều kiện nhận thêm một đơn: `maxUsage IS NULL OR usage + reservedCount < maxUsage`. Chỉ số `usage` hiển thị cho Customer và Admin là **toàn chương trình**; `reservedCount` hiển thị riêng nếu cần để giải thích tạm hết lượt. `maxUsage` không giới hạn số voucher đã phát: nếu cần quota phát, thiết kế `issueLimit` **riêng**, không đánh đồng với số lượt dùng.
4. Khi một Customer được cấp instance, việc đó **không tăng usage**. Khi họ dùng xong, `usage` tăng 1. Customer khác dùng cùng campaign cũng tăng bộ đếm chung. Thanh progress: `round(100*usage/maxUsage)`; ví dụ quota 100, đã dùng 35 → tất cả card campaign hiển thị **35/100 (35%)**, dù instance cá nhân vẫn chưa dùng hoặc đã dùng.
5. Cập nhật danh sách chi nhánh/điều khoản phải có `version` và policy về reservations đang giữ; order đã reserve theo điều kiện hợp lệ không bị vô hiệu âm thầm. Không cho Staff/quầy khác store tự sửa storeId.

### 2.2. Instance riêng và sinh mã có salt

- Một `customer_voucher_instance` gắn `id UUID`, `campaign_id`, `customer_id`, `state`, `reserved_order_id?`, `redeemed_order_id?`, `issued_at`, `expires_at?`, `version`; **UNIQUE(campaign_id,customer_id)** để một Customer chỉ nhận một voucher từ mỗi campaign. `state` có thể là `ISSUED/RESERVED/REDEEMED/EXPIRED/REVOKED`; không cần `instance.maxUsage` hay counter 2/5. Chỉ instance `ISSUED` còn hiệu lực được reserve.
- Backend sinh một **bí mật ngẫu nhiên tối thiểu 128 bit bằng CSPRNG**, encode kiểu Crockford Base32 (~26 ký tự cho 130 bit) theo nhóm dễ đọc; thêm mã định vị không bí mật, phiên bản định dạng và checksum nếu cần. Ví dụ cấu trúc `CK1-<selector>-<26+ ký tự random>` (chuỗi minh hoạ, không hardcode thành mã thật). **Mỗi voucher là một mã khác nhau** ngay cả khi cùng campaign.
- Lưu `selector` indexed/unique để tìm đúng record; lưu **salt ngẫu nhiên riêng từng voucher** và verifier `HMAC-SHA256(serverPepper, salt || normalizedSecret)` (hoặc KDF mạnh tương đương), so sánh constant-time. Đây là **salt per-record + server secret**, không phải nối một salt chung cố định rồi hash. Cơ chế lookup qua selector giải quyết việc hash có salt không thể tìm bằng cách truy vấn trực tiếp trên toàn bảng. Phải quản lý version/rotation của key.
- Để Customer mở modal xem lại đúng mã đã cấp, lưu mã được **mã hoá AES-GCM với khoá riêng quản lý qua secret/KMS** (hoặc cơ chế phục hồi tương đương). Không lưu duy nhất hash không thể khôi phục mà UI lại cần xem lại. Không đưa mã thô vào log, analytics, query URL hay thông báo lỗi; chỉ trả mã qua endpoint owner-only, và chỉ lưu verifier/salt/ciphertext phía DB.
- Claim bằng JWT Customer; gọi lại cùng campaign hoặc retry `Idempotency-Key` trả instance đã có, không cấp thêm mã. Mã còn ở trạng thái `REDEEMED` sau refund **không được nhận lại** bằng nhấn “Nhận voucher”.
- QR chứa định dạng version + **chính mã voucher cá nhân** (ví dụ `CLUCKY:V1:<code>`), không chứa JWT/phone/email/customerId. Scan hay nhập tay đi qua một resolver/validator và cùng instance. QR tĩnh vẫn hợp lệ khi chưa redeem; sau redeem/hết hạn/cancel thay đổi trạng thái xử lý theo order. Khi hủy hợp lệ, cùng mã có thể được dùng lại.

### 2.3. Voucher ở Staff POS: đúng mã là dùng

1. `frontend/react` tại quầy có nút **Quét QR Voucher** (modal camera thật: `getUserMedia`, hỗ trợ từ chối permission, đổi camera, cleanup khi đóng) và ô **Nhập mã voucher**, normalize khoảng trắng/gạch/chữ hoa, nút Áp dụng. Camera lỗi thì Staff vẫn nhập mã được. Có nút **Quét QR Loyalty** riêng ở khối điểm: scanner có thể dùng chung camera component nhưng backend phải phân loại QR qua prefix/version `CLUCKY:V1:...` (Voucher) và `CLUCKY:LOYALTY:V1:...` (Loyalty); không dùng token thuộc loại này ở API của loại kia.
2. API Staff nhận mã, resolve selector + verify salt/HMAC + check instance `ISSUED`, campaign active/thời hạn, chi nhánh, min order/discount và quota toàn hệ thống; trả preview giá. **Không yêu cầu OTP hay thông tin chủ voucher; không yêu cầu order gắn customerId của người sở hữu mã.**
3. Nếu đơn quầy không gắn tài khoản, voucher **vẫn dùng được** qua mã. Nếu đơn có gắn một account khác, việc chấp nhận mã tại quầy vẫn theo quyền sở hữu **mã**, không tự thay đổi chủ instance; audit ghi instanceOwnerId riêng với orderCustomerId nếu có. Không phát lộ tên, SĐT hay lịch sử chủ mã cho Staff.
4. Chống đoán mã bằng entropy cao, chống brute-force/rate-limit theo actor/store/IP, giảm thông tin trong lỗi mã không tồn tại, không log mã, RBAC Staff/store scope, kiểm tra chính sách lần nữa ngay lúc reserve. Đây là các lớp bảo vệ **không tạo thêm OTP**.
5. Quét/nhập **mã Voucher** chỉ tạo **preview**; Staff đổi món/chi nhánh hoặc bỏ voucher phải quote lại. Chỉ khi create order hợp lệ mới reserve. Nếu Staff đồng thời chọn **Dùng điểm**, Order còn bắt buộc kiểm tra **Loyalty scan proof** của Phase 1; việc có Voucher token/instance không thay thế quyền dùng điểm. Biên nhận hiển thị mức giảm voucher, điểm đã dùng và tổng cần thu từ Order service.
6. Thông báo trực tiếp trong modal: sai mã `VOUCHER_NOT_FOUND`; không đúng chi nhánh `VOUCHER_SCOPE_MISMATCH`; voucher đã dùng/có đơn đang giữ hoặc campaign hết quota `VOUCHER_EXHAUSTED` với `quotaScope=INSTANCE|INSTANCE_RESERVED|CAMPAIGN`; chưa đến hạn `VOUCHER_NOT_STARTED`; hết hạn `VOUCHER_EXPIRED`; không hoạt động `VOUCHER_INACTIVE`; chưa đủ giá trị đơn `MIN_ORDER_NOT_MET`; quá số lần thử `TOO_MANY_REQUESTS`. Dùng `contracts/errors.json` làm nguồn, không tự tạo error catalog trùng.

### 2.4. Customer và Admin UI

- `frontend/nextjs/app/vouchers/page.tsx`: tab **Khám phá ưu đãi** (campaign chưa nhận) và **Voucher của tôi** (instance được cấp). Card có title, điều kiện, trạng thái, chi nhánh, **progress toàn campaign** `usage/max_usage`, nhãn “Lượt sử dụng toàn hệ thống”, không có progress “2/5 lần của bạn”.
- Card cá nhân hiển thị rõ `Chưa dùng/Đang giữ/Đã sử dụng/Hết hạn`, với hành động **Xem QR & mã** khi phù hợp. Modal cho thấy QR, mã dạng chữ để đọc/copy, toàn bộ chi nhánh áp dụng (hoặc “Tất cả chi nhánh”), giá trị giảm, thời hạn và usage toàn hệ thống.
- Nếu quota không giới hạn, không render thanh % sai; nếu `usage+reservedCount=maxUsage` nhưng chưa settle hết, báo “Lượt còn lại đang xử lý”. Progress chỉ dựa **usage đã settle**. Role/proof của endpoint “my” dựa vào Customer JWT.
- Web checkout **chỉ chọn instance trong danh sách voucher của tài khoản đang đăng nhập**, có `voucherInstanceId` thuộc actor. Backend tuyệt đối không chấp nhận instance của account khác chỉ bằng ID; **không có ô nhập code** trên Web. Nếu Guest web checkout được phép theo flow hiện hành, không hiện quyền chọn voucher “của tôi” khi chưa login.
- Admin/Store Manager tạo/sửa campaign không có input mã, có chọn nhiều chi nhánh, nhập **giới hạn lượt dùng toàn hệ thống**, xem `usage/maxUsage` và `reservedCount` của campaign, danh sách voucher đã cấp và audit. **Không có ô `defaultMaxUsage` per-person**.

### 2.5. Lifecycle reservation, hủy và refund

| Sự kiện | Instance cá nhân | Quota campaign | Điều kiện |
|---|---|---|---|
| Customer nhận voucher | `ISSUED` | `usage` giữ nguyên | Unique customer/campaign; không tăng lượt dùng |
| Staff quét/nhập mã hoặc Web preview | Chưa đổi state | Không giữ quota | Validate read-only, không giữ chỗ |
| Tạo order + reserve | `ISSUED → RESERVED`, gắn order | `reservedCount += 1` | Transaction lock campaign → instance; quota còn chỗ |
| `COMPLETED + PAID/COLLECTED` | `RESERVED → REDEEMED` | `reservedCount -= 1; usage += 1` | Ghi usage theo order đúng một lần |
| Hủy đơn khi đang reserve | `RESERVED → ISSUED` nếu còn hiệu lực | `reservedCount -= 1` | `CANCELLED` hợp lệ hoặc order/payment failure; release idempotent |
| **Hủy đơn sau khi đã settle** nếu được hệ thống cho phép hủy thật | **Trả instance về `ISSUED`**, giữ lịch sử cancellation/reversal | **`usage -= 1` đúng một lần** | Chỉ khi Order authoritative chuyển `CANCELLED` hợp lệ, không được giả làm refund |
| **Refund sau khi đã hoàn tất** (toàn phần hay một phần) | **Giữ `REDEEMED`**, không cấp lại mã | **`usage` giữ nguyên** | Audit refundId, không hoàn lượt |
| Hai quầy cùng dùng một mã | Chỉ một order reserve được | Chỉ giữ một lượt | Lock và unique active reservation |

**Điểm phải triển khai nhất quán:**

- Campaign `used_count/reserved_count/total_limit` hiện có là **cấp toàn hệ thống**; có thể expose alias `usage/maxUsage` trong DTO/UI nhưng không thêm bộ đếm cá nhân. Nếu sửa DB naming thì migrate rõ ràng, giữ counter và reconciliation.
- Giữ `voucher_reservations.order_id` unique/PK, `voucher_usages.order_id` unique và **`voucher_instance_id` unique đối với lượt đang thực sự có hiệu lực**. Để hủy sau settle rồi dùng lại một mã không bị constraint chặn, lưu usage event bất biến kèm `reversed_at/reversal_id` và dùng partial unique index chỉ cho usage `ACTIVE` (hoặc bảng active-redemption riêng). **Không xoá lịch sử dùng cũ để “reset” voucher**; bút toán bù quota không được âm, chống replay.
- Cùng một order tối đa một voucher. State transitions phải idempotent theo orderId/eventId/refundId/cancelId; consumer đối chiếu Order authoritative qua internal service token và thanh toán thực. Retry/replay không được tăng hoặc trừ usage lần hai; khi service rớt dùng reconciliation sửa orphan holds.
- **CANCELLED** và **REFUNDED** là hai ý nghĩa nghiệp vụ riêng; chỉ sự kiện huỷ đơn thật mới hoàn voucher. Không tự map một refund thành CANCELLED để hoàn code. Nếu hệ thống không cho chuyển `COMPLETED → CANCELLED`, chỉ áp dụng nhánh hủy trước completed, không tạo chuyển trạng thái mới ngoài workflow.
- Voucher cũ mã campaign chung giữ **đọc đơn, reconcile, settle/refund và các đơn đang chạy** qua legacy path; ngừng tạo campaign code mới. Với chương trình legacy còn hiệu lực, cho đường tương thích hiện hành chạy đến khi kết thúc theo chính sách cũ, không tự chuyển mã cũ thành mã cá nhân. UI Web mới không mở ô nhập mã; các mã cá nhân mới chỉ được chọn từ ví.

### 2.6. DB, API và migration Phase 2 (dự kiến)

**Promotion schema:**

- Mở rộng `promotion_service.vouchers`: `campaign_kind=LEGACY|PERSONAL`, `applicability=ALL|SELECTED`, `code NULL` với PERSONAL, `max_usage/total_limit` global và `used_count/reserved_count` global; constraints cho dữ liệu mới mà không phá voucher legacy. **Không có `default_max_usage` của instance**.
- Bảng `voucher_campaign_stores(campaign_id,store_id)` với PK kép; tồn tại/role store xác thực qua Catalog. `customer_voucher_instances` chứa instance owner, trạng thái, selector, salt, verifier, ciphertext, phiên bản key, order đang reserve và order đã redeem, thời hạn và version; unique `(campaign_id,customer_id)`.
- Mở rộng `voucher_reservations` và `voucher_usages` để tham chiếu instance UUID, voucher campaign, trạng thái/reversal. Backfill legacy và thay CHECK theo `campaign_kind`. Không sửa migration V2/V7 hay V11/V12 đã áp dụng; tạo migration mới kế tiếp sau phiên bản cao nhất tại thời điểm code.
- Order lưu `customer_voucher_instance_id UUID`, `voucher_campaign_id`, `voucher_discount_amount`, `voucher_policy_version` và snapshot giá; không FK xuyên service. Order đang lưu code legacy tiếp tục đọc được để đối soát.

| Endpoint dự kiến | Quyền | Hành vi |
|---|---|---|
| `POST/PUT /api/promotions/vouchers[/{id}]` | ADMIN | Campaign **không input code**, stores nhiều/tất cả, giới hạn lượt toàn hệ thống, optimistic version |
| `POST /api/promotions/vouchers/store-proposals` | STORE_MANAGER | Proposal theo scope quán, duyệt theo workflow hiện tại |
| `GET /api/promotions/vouchers/available` | Public/CUSTOMER | Campaign summary + `usage/maxUsage/reservedCount` toàn hệ thống, không trả private code |
| `POST /api/promotions/vouchers/{campaignId}/claim` | CUSTOMER | Cấp một instance của actor, retry trả instance cũ |
| `GET /api/promotions/vouchers/mine` | CUSTOMER | Instances của actor, state và progress **campaign** |
| `GET /api/promotions/vouchers/my/{instanceId}` | CUSTOMER owner | Chi tiết + mã được giải mã cho QR/modal + chi nhánh |
| `POST /api/orders/counter/vouchers/preview` | STAFF/STORE_MANAGER/ADMIN | `{code, orderDraft}` từ QR hoặc nhập tay, **không OTP/owner check**, trả discount hoặc lỗi |
| `POST /api/orders/counter` | Staff role | Quote + voucher reference + idempotency, reserve cùng Order, không tin client price |
| `POST /api/orders/preview` & checkout Web | CUSTOMER | `voucherInstanceId` thuộc JWT actor, **không chấp nhận code nhập tay** |
| Internal Promotion voucher reserve/settle/release/reverse | Order service only | Kiểm quota, điều kiện chi nhánh, trạng thái, cancel/refund, event idempotency |

**Thứ tự triển khai nội bộ Phase 2:** snapshot counters/orders/legacy usage → expand Flyway và backfill mapping → resolver mã salted + encrypted code và claim → check store/quota + reserve/settle/reverse → tích hợp Order/Payment/Web/POS qua Gateway → Admin/Customer/Staff UI và i18n → bật feature flag nội bộ theo chi nhánh, theo dõi và đối soát. Khi rollback, chỉ tắt phát voucher mới; giữ consumer xử lý các đơn đang giữ và không xoá ledger/instance.

### 2.7. Nghiệm thu Phase 2

| Ca | Kết quả yêu cầu |
|---|---|
| Admin tạo campaign không nhập code, chọn 1/3/tất cả quán | Form/API thành công, store scope đúng; manager không vượt quyền |
| Campaign `maxUsage=100`, người A/B nhận voucher | Hai mã random khác nhau; mỗi account chỉ có một instance; **progress cùng là `usage/100`** trên cả hai, chưa dùng vẫn 0/100 |
| A dùng xong một lần | Instance A `REDEEMED`, không thể dùng tiếp; campaign usage tăng 1; B vẫn dùng được khi quota còn |
| Campaign còn `usage=99,reserved=1,maxUsage=100` | Không reserve thêm; trả `VOUCHER_EXHAUSTED` phạm vi CAMPAIGN, progress vẫn hiển thị 99/100 |
| Mã đoán/nhập sai/quét QR sai, chi nhánh sai | API lỗi rõ, không lộ PII hoặc mã, không trừ usage; rate-limit khi dò nhiều |
| Staff nhận mã đúng của A, không nhập account/OTP | Áp voucher hợp lệ tại đúng quán, giảm tiền đúng; mã nhập tay và QR cho cùng kết quả |
| Staff nhập mã Voucher đúng nhưng chỉ nhập SĐT hoặc mã Loyalty chữ | Áp dụng **giảm Voucher + tích điểm**; **không được trừ điểm Loyalty** dù ví có số dư |
| Staff nhập mã Voucher đúng và quét QR Loyalty của account còn hiệu lực | Có thể giảm cả Voucher và điểm; quote/earnBase tính trên tiền hàng sau cả hai, proof của hai loại phải độc lập |
| Quét QR Loyalty vào ô Voucher hoặc QR Voucher vào luồng Loyalty | Trả lỗi định dạng/loại mã, không đánh tráo hai quyền |
| Checkout Web đã đăng nhập | Chỉ chọn voucher đã nhận; không nhập mã; API bác instance không thuộc tài khoản |
| Hai Staff cùng dùng một mã, hoặc replay 10 lần cùng event | Một lần reserve/settle duy nhất; không double-spend, không trừ global quota 2 lần |
| Order hủy trước hoàn tất | Voucher về trạng thái dùng được và released quota; có thể dùng lại cùng mã |
| Order hợp lệ bị CANCELLED sau settle (nếu workflow hỗ trợ) | Có reversal/audit, voucher và một lượt quota được trả **đúng một lần** |
| Refund toàn bộ/một phần sau completed | Voucher vẫn `REDEEMED`; quota `usage` không giảm, không cấp lại code |
| Hoàn thành đơn có voucher + dùng điểm | Order total và earnBase tính từ tiền hàng **sau voucher và trừ điểm**, thống nhất Phase 1 |
| Legacy trước migration; rollback feature flag | Mọi order/voucher đang giữ hoặc đã dùng vẫn đọc, settle/reconcile được; không mất lịch sử |

---

## Điều kiện hoàn thành kế hoạch và phạm vi thực thi

- **Thứ tự:** Thực hiện **Phase 1** trước để thống nhất wallet, **mã Loyalty/QR 30 giây**, quyền EARN-only nhập tay và REDEEM có QR, Order amount, loyalty claim và khoá contact; tiếp đó **Phase 2** dùng cùng quote/checkout để trừ voucher và điểm đúng một công thức, **giữ hai loại mã độc lập**. Chỉ có **hai phase**, các mục nhỏ trong mỗi phase là đầu việc thực hiện, không phải phase thứ ba.
- **Phạm vi code dự kiến:** `backend/identity-service`, `backend/promotion-loyalty-service`, `backend/cart-order-service`, `backend/payment-service` (nếu cần), `frontend/react`, `frontend/nextjs`, `contracts/errors.json`, Gateway/config nếu có route mới. Phải giữ dịch vụ độc lập chạy được, tránh SQL JOIN/FK xuyên service, và bảo vệ RBAC/store scope ở backend.
- **Triển khai thực tế sau khi duyệt:** xem migration version hiện hành; backup/snapshot số dư và quota; dùng Flyway expand/backfill/constraint mới; ưu tiên chuyển schema tương thích; chạy các bước kiểm thử, chống race/replay và đối soát bằng order/payment snapshot thật. Với sửa code/config ảnh hưởng ứng dụng, rebuild chỉ service ảnh hưởng theo `./start-docker.ps1 -Services ...`; test/lint/typecheck/Playwright riêng chỉ khi người dùng yêu cầu, theo `AGENTS.md`.
- **Trạng thái triển khai 14/11/2026:** Đã triển khai code cả Phase 1 (wallet PHONE/CUSTOMER, mã chữ và QR Loyalty động 30 giây, tích điểm qua nhập tay, đổi điểm qua scan proof, hạn mức, claim sau xác thực phone, khoá contact verified, tích hợp POS/Web và ledger) và Phase 2 (campaign voucher cá nhân nhiều chi nhánh, quota toàn chương trình, cấp mã cá nhân có salt/HMAC và mã hoá AES-GCM, quét/nhập tại POS, checkout Web dùng instance của chính tài khoản, cancel/reconcile/refund audit). Các thay đổi hiện nằm trên working tree nhánh main, chưa commit/push.
- **Migration và backup:** Đã áp dụng thành công Flyway Promotion V10/V11/V12, Identity V13, Order V22/V23 ở Docker local. Đã sao lưu ba schema trước V12 vào .backups/clucky-pre-v12-20261114.dump; bản sao được Git ignore và không xóa volumes.
- **Kiểm tra kỹ thuật:** Đã chạy start-docker.ps1 -Services cho các backend và frontend bị ảnh hưởng, build thành công, container healthy. Không chạy test/lint/typecheck/Playwright riêng theo AGENTS.md; các bước build/healthcheck tích hợp Docker được thực hiện. Người dùng tự kiểm thử nghiệp vụ: OTP/claim, tích/đổi điểm quầy, voucher, đồng thời, huỷ và hoàn tiền.
- **Giới hạn refund:** RefundRequestService hiện hoàn toàn bộ tổng đơn, vì vậy Loyalty đã triển khai bù điểm cho hoàn toàn phần, ghi sổ bù/idempotency và giữ voucher đã dùng không được cấp lại. Nếu hệ thống sau này có hoàn một phần, cần mở rộng phép phân bổ tiền hàng/ship và điểm trước khi bật luồng đó.
