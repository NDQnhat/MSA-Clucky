# SePay VietQR thật cho POS Clucky — cấu hình & nghiệm thu

> **Ngày:** 15/11/2026. **Áp dụng:** Chuyển khoản VietQR tại quầy, kể cả đơn kết hợp tiền mặt/chuyển khoản nếu backend cung cấp phiên hai nguồn đã xác minh. Không dùng xác nhận chuyển khoản thủ công như bằng chứng tiền đã đến ngân hàng.

## Nguyên tắc xác nhận

1. Staff tạo đơn hàng quầy; backend Order tính tổng tiền đã trừ khuyến mãi/điểm. **Đơn 0đ không có VietQR và không tạo giao dịch 0đ.**
2. Staff mở phiên thanh toán VietQR từ Payment Service qua API Gateway. Backend xác nhận nhân viên đúng chi nhánh và lấy **tài khoản ngân hàng đã gán cho chính chi nhánh**; chỉ backend sinh mã chuyển khoản/tham chiếu cho phiên. Tất cả thông tin số tiền và tài khoản trong QR phải khớp phiên này.
3. Khách quét QR bằng app ngân hàng. SePay nhận biến động số dư của **tài khoản đã liên kết**, gửi webhook đến backend qua Gateway.
4. Payment Service kiểm tra API key, nguồn tiền vào, tài khoản ngân hàng, mã tham chiếu, số tiền đã nhận, hạn phiên và khả năng đối soát. ID giao dịch SePay được lưu duy nhất, retry không cộng hai lần. Không tự nhận thanh toán chỉ vì Staff bấm nút, mở QR, thấy ảnh chụp hoặc hết thời gian.
5. React Staff chỉ **đọc trạng thái thanh toán của Payment Service** bằng polling; chưa đủ tiền thì hiển thị thiếu tiền, trùng/sai nội dung hoặc tài khoản thì yêu cầu đối soát, không báo “đã thanh toán”. Phiên hết hạn/đơn hủy không được tự chuyển thành PAID. Tiền thừa hoặc giao dịch muộn cần quy trình hoàn/đối soát riêng.

## Cấu hình vận hành cần làm trên môi trường thật

1. Đăng nhập **[SePay](https://my.sepay.vn/)**. Liên kết tài khoản ngân hàng nhận tiền của từng quán theo chính sách vận hành. Trên Clucky Admin, kiểm tra tài khoản ngân hàng đang hoạt động và đã được **gán vào đúng chi nhánh**; tài khoản chưa được gán sẽ khiến POS không thể mở QR thật.
2. Trong SePay, tạo webhook sự kiện **Có tiền vào**, chọn đúng tài khoản ngân hàng và định dạng **JSON**. URL công khai qua HTTPS của Clucky là `https://<public-api-host>/api/payments/webhook/sepay` (thay `<public-api-host>` bằng domain thực tế được triển khai; **không dùng localhost**).
3. Chọn xác thực webhook **API Key**, đặt khóa trùng với biến bí mật `SEPAY_API_KEY` của `payment-service` trong cấu hình backend của môi trường tương ứng. Header từ SePay là `Authorization: Apikey <key>`. **Không đưa khóa vào frontend, image, Git, log, hoặc URL QR.** Không chọn webhook “Không xác thực”.
   - Cờ `SEPAY_POS_ENABLED` bật phiên VietQR thật ở POS. Mặc định `false` để tránh tự mở QR thu tiền khi webhook public chưa được cấu hình; chỉ đặt `true` trong **môi trường đã xác nhận callback, khóa API và tài khoản nhận tiền đúng**. Sau khi cập nhật environment, triển khai lại `payment-service` qua quy trình CI/CD/Docker.
4. Nếu bật bộ lọc mã thanh toán/prefix của SePay, bảo đảm bộ lọc **không bỏ các mã tham chiếu do Payment Service sinh ra**. Bộ lọc sai sẽ khiến giao dịch thật không được báo về Clucky.
5. Kiểm tra Gateway cho phép **chỉ POST** vào endpoint webhook công khai; Payment Service vẫn tự xác thực API key. Cấu hình proxy/WAF/CDN không chặn SePay và không bỏ header `Authorization`. Endpoint nên phản hồi JSON `{"success":true}` và mã HTTP 200/201 sau khi đã lưu giao dịch thành công.
6. Trong nhật ký webhook của SePay, kiểm tra mã phản hồi, số lần retry và đối chiếu receipt với màn giao dịch/quản lý đối soát Clucky. SePay có thể gửi lại webhook; callback phải luôn idempotent. Không giả mạo xác nhận bằng endpoint nhân viên.

## Kịch bản nghiệm thu thủ công (người vận hành)

- **Tài khoản đúng quán:** Staff quán A mở QR đúng ngân hàng/tài khoản được gán cho A; Staff quán B không được truy cập phiên A. Quán chưa gán tài khoản báo không khả dụng, không sinh QR giả.
- **Chuyển đủ:** Tạo đơn quầy >0đ, quét QR với đúng nội dung/số tiền, chỉ sau khi webhook hợp lệ thì POS và Order ghi nhận thanh toán. Mở lại trang và tải lại không mất trạng thái.
- **Thiếu/dư/sai:** Chuyển thiếu một phần, chuyển hai giao dịch cộng đủ, chuyển dư, nội dung tham chiếu sai, giao dịch nhầm tài khoản, cùng webhook gửi lại hai lần, giao dịch khác ID nhưng cùng nội dung — kiểm số tiền nhận và trạng thái không cộng trùng hoặc nhận nhầm đơn.
- **Tiền mặt + chuyển khoản:** Số tiền cash + tiền SePay khớp tổng do server tính; cash không đủ để xác nhận cả đơn nếu chưa nhận đủ phần bank; hai lần click/retry mở cùng phiên và không ghi hai khoản cash.
- **Hết hạn/cancel:** Hết hạn khi chưa chuyển, hủy đơn rồi chuyển muộn, webhook đến đồng thời khi hết hạn; hệ thống không biến đơn đã hủy thành thành công hoặc tự cho chuyển khi phiên đóng.
- **Sự cố SePay:** Tắt tạm backend callback hoặc sai API key trong môi trường thử nghiệm, xác nhận POS vẫn báo đang chờ/không khả dụng chứ không tự PAID; kiểm tra nhật ký retry của SePay, sau đó phát lại có kiểm soát.

## Tài liệu chính thức

- [SePay — Tạo trang QR thanh toán và polling trạng thái](https://developer.sepay.vn/vi/sepay-webhooks/tao-qr-va-form-thanh-toan)
- [SePay — Hướng dẫn tích hợp webhook, xác thực, phản hồi, retry](https://docs.sepay.vn/tich-hop-webhooks.html)

**Giới hạn nghiệm thu:** Docker build/health không chứng minh ngân hàng/SePay đã chuyển tiền hoặc callback public đang nhận dữ liệu. Không dùng giao dịch thật để kiểm thử tự động nếu chưa được chủ tài khoản ngân hàng cho phép.
