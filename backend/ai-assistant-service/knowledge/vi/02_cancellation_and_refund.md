# Chính sách hủy đơn và hoàn tiền tại Clucky

## Quy tắc tự hủy đơn hàng
- Khách hàng có thể tự hủy đơn khi đơn đang ở trạng thái **`PENDING_PAYMENT`** (chờ thanh toán) hoặc **`CONFIRMED`** (đã xác nhận nhưng bếp chưa bắt đầu chế biến) kèm lý do hủy đơn.
- **Không thể hủy đơn** khi đơn đã chuyển sang trạng thái **`PREPARING`** (đang chế biến), **`READY`** (đã làm xong), **`SHIPPING`** (đang giao) hoặc **`COMPLETED`** (đã hoàn tất).
- Nếu đơn đã dùng điểm tích lũy hoặc voucher (trạng thái giữ chỗ), khi đơn hủy thành công trước khi chế biến, điểm giữ chỗ sẽ được giải phóng lại vào ví điểm của bạn.

## Điều kiện và quy trình hoàn tiền (`BANK_TRANSFER`)
Khách hàng có thể gửi yêu cầu hoàn tiền cho đơn thanh toán chuyển khoản (`BANK_TRANSFER` đã thanh toán thành công `PAID`) trong các trường hợp hợp lệ sau:
1. Đơn đã bị hủy hợp lệ ở trạng thái `PENDING_PAYMENT` hoặc `CONFIRMED` trước khi bếp chế biến (`PREPARING`).
2. Đơn giao hàng (`DELIVERY`) có kết quả giao thất bại (`DELIVERY_FAILED`) do hệ thống giao nhận ghi nhận.

Khi tạo yêu cầu hoàn tiền cho đơn đủ điều kiện, khách hàng cần cung cấp thông tin tài khoản nhận hoàn tiền chính xác:
- Mã ngân hàng (`bankBin` 6 chữ số) và tên ngân hàng (`bankName`)
- Số tài khoản (`bankAccountNo`)
- Tên chủ tài khoản (`bankAccountName`)
- Lý do yêu cầu hoàn tiền (`reason`)

Đơn thanh toán `COD` chưa thu tiền khi chưa giao thành công sẽ không phát sinh giao dịch hoàn tiền chuyển khoản.
