# Chính sách đặt hàng và thanh toán tại Clucky

## Hình thức nhận hàng
Clucky hỗ trợ 2 hình thức đặt món trực tuyến trên website:
1. **Giao hàng tận nơi (`DELIVERY`)**: Giao từ chi nhánh Clucky bạn đã chọn đến địa chỉ giao hàng (nằm trong bán kính giao hàng của chi nhánh).
2. **Nhận tại cửa hàng (`PICKUP`)**: Bạn đặt trước và thanh toán/xác nhận đơn, sau đó đến trực tiếp chi nhánh đã chọn để nhận món, không mất phí giao hàng.

Lưu ý: Tất cả món trong một giỏ hàng phải thuộc cùng một chi nhánh. Nếu bạn đổi chi nhánh, vui lòng kiểm tra lại giá và tình trạng còn món (`isSoldOut`) tại chi nhánh mới.

## Phương thức thanh toán
1. **Thanh toán khi nhận hàng (`COD`)**:
   - Với đơn `DELIVERY`: Thanh toán tiền mặt cho nhân viên giao hàng khi nhận món.
   - Với đơn `PICKUP`: Thanh toán trực tiếp tại quầy khi đến nhận đơn.
   - Đơn `COD` sau khi đặt thành công sẽ chuyển thẳng sang trạng thái `CONFIRMED` để bếp chuẩn bị.
2. **Chuyển khoản ngân hàng qua VietQR (`BANK_TRANSFER`)**:
   - Sau khi đặt đơn, hệ thống tạo mã VietQR kèm nội dung chuyển khoản riêng cho đơn hàng.
   - Đơn ở trạng thái chờ thanh toán (`PENDING_PAYMENT`) cho đến khi giao dịch chuyển khoản được hệ thống đối soát tự động qua SePay và xác nhận (`CONFIRMED`).

## Quy trình trạng thái đơn hàng
- `PENDING_PAYMENT`: Chờ khách hàng hoàn tất chuyển khoản VietQR.
- `CONFIRMED`: Đơn đã được xác nhận và chuyển tới chi nhánh.
- `PREPARING`: Bếp đang chế biến món ăn.
- `READY`: Món đã sẵn sàng (chờ shipper lấy với `DELIVERY`, hoặc sẵn sàng tại quầy với `PICKUP`).
- `SHIPPING`: Shipper đang giao hàng (áp dụng với đơn `DELIVERY`).
- `COMPLETED`: Đơn hàng đã giao/nhận thành công và tích điểm thành viên (nếu có tài khoản).
- `CANCELLED` / `DELIVERY_FAILED`: Đơn đã hủy hoặc giao thất bại.
