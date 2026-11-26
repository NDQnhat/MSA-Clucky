package com.clucky.notificationservice.consumer;

import com.clucky.notificationservice.dto.DomainEvent;
import com.clucky.notificationservice.dto.LocalizedText;
import com.clucky.notificationservice.entity.Notification;
import com.clucky.notificationservice.logging.SafeLog;
import com.clucky.notificationservice.mapper.EventMapper;
import com.clucky.notificationservice.repository.NotificationRepository;
import com.clucky.notificationservice.service.EmailSenderService;
import com.clucky.notificationservice.service.CustomerContactClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class NotificationConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationConsumer.class);

    private final EventMapper eventMapper;
    private final NotificationRepository notificationRepository;
    private final EmailSenderService emailSenderService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final String customerUrl;
    private final CustomerContactClient contacts;

    public NotificationConsumer(
            EventMapper eventMapper,
            NotificationRepository notificationRepository,
            EmailSenderService emailSenderService,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            @org.springframework.beans.factory.annotation.Value("${clucky.frontend.customer-url}") String customerUrl,
            CustomerContactClient contacts
    ) {
        this.eventMapper = eventMapper;
        this.notificationRepository = notificationRepository;
        this.emailSenderService = emailSenderService;
        this.jdbc = jdbc;
        this.contacts = contacts;
        var origin = java.net.URI.create(customerUrl);
        if (!java.util.Set.of("http", "https").contains(origin.getScheme()) || origin.getHost() == null
                || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null) {
            throw new IllegalArgumentException("Configure the customer application URL for notifications");
        }
        this.customerUrl = customerUrl.replaceAll("/+$", "");
    }

    @KafkaListener(
            topics = {
                    "order.created",
                    "order.status-changed",
                    "order.completed",
                    "order.cancelled",
                    "payment.confirmed",
                    "payment.failed",
                    "promotion.approved",
                    "refund.approved",
                    "refund.rejected",
                    "refund.waiting-bank-details",
                    "refund.confirmed",
                    "refund.processed",
                    "inventory.low-stock"
            },
            groupId = "${spring.kafka.consumer.group-id:notification-group}"
    )
    @Transactional
    public void consume(ConsumerRecord<String, String> record, org.springframework.kafka.support.Acknowledgment ack) {
        String topic = record.topic();

        // Check for promotion.approved topic - explicitly deferred per INT-41/58
        if ("promotion.approved".equals(topic)) {
            LOG.info("event=promotion_approved_deferred topic={} partition={} offset={} (deferred per INT-41/58)",
                    topic, record.partition(), record.offset());
            if (ack != null) {
                ack.acknowledge();
            }
            return;
        }

        DomainEvent event;
        try {
            event = eventMapper.fromJson(record.value(), topic);
        } catch (Exception ex) {
            LOG.warn("event=kafka_event_malformed topic={} partition={} offset={}",
                    SafeLog.token(topic), record.partition(), record.offset());
            throw new IllegalArgumentException("Invalid notification event");
        }

        String eventId = event.eventId();
        String correlationId = event.correlationId();
        MDC.put("eventId", SafeLog.token(eventId));
        MDC.put("correlationId", correlationId);

        try {
            processEvent(event);
            if (ack != null) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        ack.acknowledge();
                    }
                });
            }
        } finally {
            MDC.remove("eventId");
            MDC.remove("correlationId");
        }
    }

    private void processEvent(DomainEvent event) {
        JsonNode payload = event.payload();
        if (payload == null || !payload.isObject()) {
            LOG.warn("event=kafka_event_skipped_invalid_payload eventId={} eventType={}",
                    event.eventId(), event.eventType());
            return;
        }

        boolean refundEvent = event.eventType().startsWith("refund.");
        if (event.eventType().startsWith("order.") && !"cart-order-service".equals(event.sourceService())) {
            throw new IllegalArgumentException("Invalid order notification source");
        }
        if (refundEvent && !"cart-order-service".equals(event.sourceService())
                && !"payment-service".equals(event.sourceService())) {
            throw new IllegalArgumentException("Invalid refund notification source");
        }
        if ("order.created".equals(event.eventType())) {
            recordAdminOrder(event);
        }
        if ("inventory.low-stock".equals(event.eventType())) {
            if (!"catalog-store-service".equals(event.sourceService())) {
                throw new IllegalArgumentException("Invalid inventory notification source");
            }
            recordAdminAlert(event, "INVENTORY", positiveId(payload, "productId"),
                    LocalizedText.of("Tồn kho thấp", "Low Stock Alert"),
                    LocalizedText.of(
                            "Chi nhánh có món sắp hết. Kiểm tra tồn kho để điều chỉnh khả năng bán.",
                            "A branch item is running low. Please check inventory availability."));
        }
        // Inventory recipient IDs must be resolved from current Identity data.
        // A managerUserId embedded in an event is not a verified recipient.
        Long customerId = refundEvent ? refundCustomerId(payload) : positiveId(payload, "customerId");
        Long orderId = extractOrderId(payload);
        if (event.eventType().startsWith("order.") && (positiveId(payload, "orderId") == null
                || (customerId == null && !"COUNTER".equals(payload.path("orderSource").asText())))) {
            throw new IllegalArgumentException("Invalid order notification identity");
        }
        if ("refund.processed".equals(event.eventType())
                && (positiveId(payload, "refundId") == null || orderId == null || orderId <= 0
                || positiveId(payload, "storeId") == null || !"REFUNDED".equals(payload.path("status").asText())
                || !payload.path("amount").isNumber() || payload.path("amount").decimalValue().signum() <= 0)) {
            throw new IllegalArgumentException("Invalid processed refund notification");
        }
        if ("refund.processed".equals(event.eventType())) {
            recordAdminAlert(event, "ORDER", orderId,
                    LocalizedText.of("Đã ghi nhận hoàn tiền", "Refund Recorded"),
                    LocalizedText.of(
                            "Đơn hàng #" + orderId + " đã được ghi nhận hoàn tiền.",
                            "Refund has been recorded for order #" + orderId + "."));
        }
        if (event.eventType().startsWith("payment.")) {
            // A real SePay receipt may confirm a COUNTER order, which has no
            // customer account. Keep its outcome idempotent without creating
            // customer notifications/email for a fabricated recipient.
            boolean branchCounterPayment = customerId == null
                    && "COUNTER".equals(payload.path("orderSource").asText())
                    && positiveId(payload, "storeId") != null;
            if (!"payment-service".equals(event.sourceService()) || orderId == null || orderId <= 0
                    || (customerId == null && !branchCounterPayment)) {
                throw new IllegalArgumentException("Invalid payment notification identity");
            }
            jdbc.update("INSERT INTO notification_service.payment_outcomes(order_id,outcome) VALUES (?,'PENDING') ON CONFLICT DO NOTHING",orderId);
            var outcome=jdbc.queryForMap("SELECT * FROM notification_service.payment_outcomes WHERE order_id=? FOR UPDATE",orderId);
            boolean confirmed="payment.confirmed".equals(event.eventType());
            if (Boolean.TRUE.equals(outcome.get("confirmed")) || !confirmed && Boolean.TRUE.equals(outcome.get("failed"))) return;
            jdbc.update(confirmed ? "UPDATE notification_service.payment_outcomes SET confirmed=true,outcome='CONFIRMED',updated_at=now() WHERE order_id=?"
                    : "UPDATE notification_service.payment_outcomes SET failed=true,outcome='FAILED',updated_at=now() WHERE order_id=?",orderId);
        }
        String orderCode = extractOrderCode(payload, orderId);
        if (refundEvent && customerId == null) {
            jdbc.update("""
                    INSERT INTO notification_service.notification_delivery_gaps(event_id,event_type,correlation_id,reason)
                    VALUES (?,?,?,?) ON CONFLICT DO NOTHING
                    """, event.eventId(), event.eventType(), event.correlationId(),
                    "NO_VERIFIED_CUSTOMER_RECIPIENT");
        }
        String deliveryKey = "refund.processed".equals(event.eventType())
                ? "refund.processed:" + positiveId(payload, "refundId") : event.eventType() + ":" + event.eventId();

        NotificationSpec spec = resolveNotificationSpec(event.eventType(), payload, orderCode);
        if (spec == null) {
            LOG.info("event=notification_no_action_needed eventId={} eventType={}", event.eventId(), event.eventType());
            return;
        }

        // These intents are written with the source event in the same transaction.
        // A separate worker resolves current active branch accounts via Identity,
        // so outages cannot silently lose staff/manager notifications.
        if ("order.created".equals(event.eventType())) {
            Long storeId = positiveId(payload, "storeId");
            if (storeId == null || orderId == null) throw new IllegalArgumentException("Invalid branch order notification");
            for (String role : java.util.List.of("STORE_MANAGER", "STAFF")) {
                recordBranchIntent(event, "order:" + orderId + ":" + role, storeId, role,
                        "ORDER", orderId,
                        LocalizedText.of("Đơn hàng mới", "New Order"),
                        LocalizedText.of(
                                "Đơn hàng #" + orderCode + " vừa được tạo tại chi nhánh của bạn.",
                                "Order #" + orderCode + " has been placed at your branch."));
            }
        } else if ("inventory.low-stock".equals(event.eventType())) {
            Long storeId = positiveId(payload, "storeId");
            Long productId = positiveId(payload, "productId");
            String episodeId = payload.path("episodeId").asText("");
            if (storeId == null || productId == null) throw new IllegalArgumentException("Invalid inventory notification scope");
            // Legacy events may predate episode IDs. Keep their event identity
            // stable across replay without dropping the entire Kafka record.
            if (!episodeId.matches("[0-9a-fA-F-]{36}")) episodeId = event.eventId();
            recordBranchIntent(event, "low-stock:" + storeId + ":" + productId + ":" + episodeId,
                    storeId, "STORE_MANAGER", "INVENTORY", productId,
                    spec.inAppTitle(), spec.inAppContent());
        }

        // 1. In-App Notification (Idempotent by event + channel + recipient)
        if (customerId != null && spec.inAppTitle() != null) {
            String inAppKey = java.util.UUID.nameUUIDFromBytes(("IN_APP:" + deliveryKey + ":" + customerId)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            Optional<Notification> existing = notificationRepository.findByOperationKey(inAppKey);
            if (existing.isPresent()) {
                LOG.info("event=notification_in_app_replay_skipped operationKey={}", inAppKey);
            } else {
                jdbc.update("""
                        INSERT INTO notification_service.notifications(user_id,title,content,type,ref_id,operation_key)
                        VALUES (?,?::jsonb,?::jsonb,?,?,?) ON CONFLICT (operation_key) WHERE operation_key IS NOT NULL DO NOTHING
                        """, customerId, spec.inAppTitle().toJson(), spec.inAppContent().toJson(), spec.inAppType(), orderId, inAppKey);

                LOG.info("event=notification_in_app_created userId={} type={} refId={} operationKey={}",
                        customerId, spec.inAppType(), orderId, inAppKey);
            }
        }

        if (spec.emailTemplate() != null && customerId != null) {
            String emailMessageId = java.util.UUID.nameUUIDFromBytes(("EMAIL:" + deliveryKey + ":" + customerId)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            if (!jdbc.queryForList("SELECT id FROM notification_service.email_logs WHERE message_id=? OR (event_id=? AND template=?)",
                    emailMessageId, event.eventId(), spec.emailTemplate()).isEmpty()) return;
            // Resolve the *current* verified contact for every email, including refunds.
            // A refund can be processed long after the order was placed; its order
            // snapshot may contain an address the customer subsequently changed.
            // Never deliver sensitive refund details to a stale snapshot recipient.
            String recipientEmail = contacts.verifiedEmail(customerId);
            if (recipientEmail == null) {
                jdbc.update("INSERT INTO notification_service.notification_delivery_gaps(event_id,event_type,correlation_id,reason) "
                        + "VALUES (?,?,?,'NO_VERIFIED_CUSTOMER_EMAIL') ON CONFLICT DO NOTHING",
                        event.eventId(), event.eventType(), event.correlationId());
                return;
            }
            emailSenderService.processEmailIntent(
                    emailMessageId,
                    event.eventId(),
                    event.correlationId(),
                    recipientEmail.trim(),
                    spec.emailTemplate(),
                    spec.emailVariables(),
                    positiveId(payload, "storeId"),
                    null
            );
        }
    }

    private NotificationSpec resolveNotificationSpec(String eventType, JsonNode payload, String orderCode) {
        Map<String, String> vars = new HashMap<>();
        String locale = payload.hasNonNull("locale") ? payload.get("locale").asText("vi") : "vi";
        boolean isEn = "en".equalsIgnoreCase(locale);
        vars.put("locale", isEn ? "en" : "vi");
        vars.put("orderCode", orderCode);
        vars.put("customerName", extractCustomerName(payload, isEn));
        vars.put("amount", extractAmount(payload));
        Long orderId = positiveId(payload, "orderId");
        if (orderId != null) {
            vars.put("orderId", orderId.toString());
            vars.put("orderUrl", customerUrl + "/orders/" + orderId);
        }
        if (eventType.startsWith("order.")) addOrderVariables(payload, vars, isEn);

        return switch (eventType) {
            case "order.created" -> {
                LocalizedText title = LocalizedText.of("Đơn hàng đã được tạo", "Order Created");
                LocalizedText content = LocalizedText.of(
                        "Đơn hàng #" + orderCode + " đã được tạo thành công và đang chờ xác nhận.",
                        "Order #" + orderCode + " has been created and is awaiting confirmation.");
                String status = payload.path("status").asText();
                String template = switch (status) {
                    case "CONFIRMED" -> "ORDER_CONFIRMED";
                    case "PENDING_PAYMENT" -> "ORDER_CREATED";
                    default -> throw new IllegalArgumentException("Invalid initial order state");
                };
                yield new NotificationSpec(title, content, "ORDER", template, vars);
            }
            case "order.status-changed" -> {
                String status = payload.hasNonNull("status") ? payload.get("status").asText() : "UPDATED";
                LocalizedText title = LocalizedText.of("Cập nhật trạng thái đơn hàng", "Order Status Updated");
                LocalizedText content = LocalizedText.of(
                        "Đơn hàng #" + orderCode + " đã chuyển sang trạng thái " + status + ".",
                        "Order #" + orderCode + " has moved to " + status + ".");
                String emailTemplate = switch (status) {
                    case "CONFIRMED" -> "ORDER_CONFIRMED";
                    case "PREPARING" -> "ORDER_PREPARING";
                    case "READY" -> "ORDER_READY";
                    default -> null;
                };
                vars.put("readyInstruction", "PICKUP".equals(payload.path("type").asText())
                        ? (isEn ? "You can pick up your order at the branch using your order details."
                                : "Bạn có thể đến cửa hàng nhận món theo thông tin trong đơn.")
                        : (isEn ? "Please check pickup/delivery instructions in your order details; this status does not confirm final delivery."
                                : "Vui lòng theo dõi hướng dẫn nhận món trong chi tiết đơn; trạng thái này chưa xác nhận đã giao hàng."));
                yield new NotificationSpec(title, content, "ORDER", emailTemplate, vars);
            }
            case "order.completed" -> {
                LocalizedText title = LocalizedText.of("Đơn hàng đã hoàn tất", "Order Completed");
                LocalizedText content = LocalizedText.of(
                        "Đơn hàng #" + orderCode + " đã hoàn tất. Cảm ơn bạn đã lựa chọn Clucky!",
                        "Order #" + orderCode + " is complete. Thank you for choosing Clucky!");
                yield new NotificationSpec(title, content, "ORDER", "ORDER_COMPLETED", vars);
            }
            case "order.cancelled" -> {
                String reason = payload.hasNonNull("reason") ? payload.get("reason").asText() : "";
                vars.put("reason", reason.isBlank()
                        ? (isEn ? "Please view details in the app" : "Vui lòng xem chi tiết trong ứng dụng")
                        : reason);
                LocalizedText title = LocalizedText.of("Đơn hàng đã bị hủy", "Order Cancelled");
                LocalizedText content = LocalizedText.of(
                        "Đơn hàng #" + orderCode + " đã bị hủy." + (!reason.isBlank() ? " Lý do: " + reason : ""),
                        "Order #" + orderCode + " was cancelled." + (!reason.isBlank() ? " Reason: " + reason : ""));
                yield new NotificationSpec(title, content, "ORDER", "ORDER_CANCELLED", vars);
            }
            case "payment.confirmed" -> {
                if ("COLLECTION".equals(payload.path("operationType").asText())) {
                    yield new NotificationSpec(
                            LocalizedText.of("Đã ghi nhận thu tại quầy", "Counter Payment Collected"),
                            LocalizedText.of(
                                    "Đơn hàng #" + orderCode + " đã được chấp nhận thu tại quầy. Khoản chuyển khai báo có thể còn chờ đối soát ngân hàng.",
                                    "Counter payment for order #" + orderCode + " has been recorded. Declared bank transfers may still await reconciliation."),
                            "PAYMENT", null, vars);
                }
                LocalizedText title = LocalizedText.of("Thanh toán thành công", "Payment Confirmed");
                String amount = vars.get("amount");
                LocalizedText content = LocalizedText.of(
                        "Thanh toán cho đơn hàng #" + orderCode + " thành công" + (!amount.isBlank() ? " (" + amount + "đ)" : "") + ".",
                        "Payment for order #" + orderCode + " succeeded" + (!amount.isBlank() ? " (" + amount + " VND)" : "") + ".");
                yield new NotificationSpec(title, content, "PAYMENT", "PAYMENT_CONFIRMED", vars);
            }
            case "payment.failed" -> {
                String reason = payload.hasNonNull("reason") ? payload.get("reason").asText() : "";
                if (!reason.isBlank()) {
                    vars.put("reason", reason);
                }
                vars.put("paymentExplanation", isEn
                        ? "Please check your transaction status and instructions in the app. Clucky only confirms payment after successful reconciliation."
                        : "Vui lòng xem trạng thái giao dịch và hướng dẫn trong ứng dụng. Clucky chỉ xác nhận thanh toán sau khi đối chiếu thành công.");
                LocalizedText title = LocalizedText.of("Thanh toán thất bại", "Payment Failed");
                LocalizedText content = LocalizedText.of(
                        "Thanh toán cho đơn hàng #" + orderCode + " thất bại." + (!reason.isBlank() ? " Lý do: " + reason : ""),
                        "Payment for order #" + orderCode + " failed." + (!reason.isBlank() ? " Reason: " + reason : ""));
                yield new NotificationSpec(title, content, "PAYMENT", "PAYMENT_FAILED", vars);
            }
            case "refund.approved" -> {
                LocalizedText title = LocalizedText.of("Yêu cầu hoàn tiền đã được tiếp nhận", "Refund Request Approved");
                LocalizedText content = LocalizedText.of(
                        "Yêu cầu hoàn tiền cho đơn hàng #" + orderCode + " đã được tiếp nhận xử lý.",
                        "Your refund request for order #" + orderCode + " has been accepted for processing.");
                yield new NotificationSpec(title, content, "REFUND", "REFUND_APPROVED", vars);
            }
            case "refund.rejected" -> {
                String reason = payload.hasNonNull("reason") ? payload.get("reason").asText() : "";
                vars.put("reason", reason.isBlank()
                        ? (isEn ? "Please view details in the app" : "Vui lòng xem chi tiết trong ứng dụng")
                        : reason);
                LocalizedText title = LocalizedText.of("Yêu cầu hoàn tiền bị từ chối", "Refund Request Rejected");
                LocalizedText content = LocalizedText.of(
                        "Yêu cầu hoàn tiền cho đơn hàng #" + orderCode + " bị từ chối." + (!reason.isBlank() ? " Lý do: " + reason : ""),
                        "Your refund request for order #" + orderCode + " was declined." + (!reason.isBlank() ? " Reason: " + reason : ""));
                yield new NotificationSpec(title, content, "REFUND", "REFUND_REJECTED", vars);
            }
            case "refund.waiting-bank-details" -> {
                String reason = payload.hasNonNull("reason") ? payload.get("reason").asText() : "";
                vars.put("reason", reason.isBlank()
                        ? (isEn ? "Please verify your bank account details" : "Vui lòng kiểm tra thông tin nhận tiền")
                        : reason);
                LocalizedText title = LocalizedText.of("Cần cập nhật thông tin nhận hoàn tiền", "Bank Details Required for Refund");
                LocalizedText content = LocalizedText.of(
                        "Chuyển tiền hoàn cho đơn hàng #" + orderCode + " chưa thành công." + (!reason.isBlank() ? " Lý do: " + reason : "") + ". Vui lòng cập nhật lại thông tin tài khoản ngân hàng.",
                        "Refund transfer for order #" + orderCode + " could not be completed." + (!reason.isBlank() ? " Reason: " + reason : "") + ". Please update your bank account details.");
                yield new NotificationSpec(title, content, "REFUND", "REFUND_WAITING_BANK_DETAILS", vars);
            }
            case "refund.confirmed", "refund.processed" -> {
                LocalizedText title = LocalizedText.of("Hoàn tiền thành công", "Refund Completed");
                String amount = vars.get("amount");
                LocalizedText content = LocalizedText.of(
                        "Số tiền " + (!amount.isBlank() ? amount + "đ " : "") + "cho đơn hàng #" + orderCode + " đã được hoàn tất.",
                        "A refund of " + (!amount.isBlank() ? amount + " VND " : "") + "for order #" + orderCode + " has been completed.");
                yield new NotificationSpec(title, content, "REFUND", "REFUND_PROCESSED", vars);
            }
            case "inventory.low-stock" -> {
                String productNameVi = payload.hasNonNull("productName") ? payload.get("productName").asText() : "Món ăn";
                LocalizedText productNameI18n = LocalizedText.fromDbValue(payload.get("productNameI18n"));
                String productNameEn = productNameI18n != null ? productNameI18n.resolve("en") : productNameVi;
                int qty = payload.hasNonNull("quantity") ? payload.get("quantity").asInt() : 0;
                int threshold = payload.hasNonNull("threshold") ? payload.get("threshold").asInt() : 20;
                LocalizedText title = LocalizedText.of("Cảnh báo tồn kho thấp", "Low Stock Warning");
                LocalizedText content = LocalizedText.of(
                        "Sản phẩm " + productNameVi + " hiện chỉ còn " + qty + " phần (ngưỡng cảnh báo: " + threshold + ").",
                        "Product " + productNameEn + " only has " + qty + " units remaining (alert threshold: " + threshold + ").");
                yield new NotificationSpec(title, content, "INVENTORY", null, vars);
            }
            default -> null;
        };
    }

    private void recordBranchIntent(DomainEvent event, String operationKey, Long storeId, String role,
                                    String type, Long reference, LocalizedText title, LocalizedText content) {
        jdbc.update("""
                INSERT INTO notification_service.branch_notification_intents
                    (operation_key,event_id,store_id,role,title,content,type,ref_id)
                VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?) ON CONFLICT(operation_key) DO NOTHING
                """, "branch:" + operationKey, event.eventId(), storeId, role, title.toJson(), content.toJson(), type, reference);
    }

    private void recordAdminOrder(DomainEvent event) {
        JsonNode payload = event.payload();
        Long orderId = positiveId(payload, "orderId");
        Long storeId = positiveId(payload, "storeId");
        if (!"cart-order-service".equals(event.sourceService()) || orderId == null || storeId == null) {
            throw new IllegalArgumentException("Invalid admin order notification");
        }
        LocalizedText title = LocalizedText.of("Đơn hàng mới", "New Order");
        LocalizedText content = LocalizedText.of(
                "Đơn hàng #" + orderId + " vừa được tạo tại chi nhánh #" + storeId + ".",
                "Order #" + orderId + " was placed at branch #" + storeId + ".");
        jdbc.update("""
                INSERT INTO notification_service.admin_notifications
                    (operation_key,event_id,correlation_id,title,content,type,ref_id,store_id)
                VALUES (?,?,?,?::jsonb,?::jsonb,'ORDER',?,?) ON CONFLICT (operation_key) DO NOTHING
                """, "order.created:" + orderId, event.eventId(), event.correlationId(),
                title.toJson(), content.toJson(), orderId, storeId);
    }

    private Long positiveId(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0 ? value.longValue() : null;
    }

    private void recordAdminAlert(DomainEvent event, String type, Long reference, LocalizedText title, LocalizedText content) {
        Long storeId = positiveId(event.payload(), "storeId");
        if (reference == null || storeId == null) throw new IllegalArgumentException("Invalid admin notification reference");
        jdbc.update("INSERT INTO notification_service.admin_notifications "
                + "(operation_key,event_id,correlation_id,title,content,type,ref_id,store_id) VALUES (?,?,?,?::jsonb,?::jsonb,?,?,?) "
                + "ON CONFLICT(operation_key) DO NOTHING", "admin:" + event.eventId(), event.eventId(), event.correlationId(),
                title.toJson(), content.toJson(), type, reference, storeId);
    }

    private Long refundCustomerId(JsonNode payload) {
        Long customerId = positiveId(payload, "customerId");
        JsonNode recipient = payload.path("recipient");
        return customerId != null && customerId.equals(positiveId(recipient, "userId"))
                && "CUSTOMER".equals(recipient.path("role").asText()) ? customerId : null;
    }


    private Long extractOrderId(JsonNode payload) {
        if (payload.hasNonNull("orderId")) {
            return payload.get("orderId").asLong();
        }
        if (payload.hasNonNull("id")) {
            return payload.get("id").asLong();
        }
        return null;
    }

    private String extractOrderCode(JsonNode payload, Long orderId) {
        if (payload.hasNonNull("orderCode")) {
            return payload.get("orderCode").asText();
        }
        return orderId != null ? String.valueOf(orderId) : "N/A";
    }

    private void addOrderVariables(JsonNode payload, Map<String, String> variables, boolean isEn) {
        var displayedItems = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        var items = payload.path("items");
        if (items.isArray()) {
            int encodedLength = 2;
            for (JsonNode item : items) {
                LocalizedText nameI18n = LocalizedText.fromDbValue(item.path("itemNameI18n").isMissingNode()
                        ? item.path("itemName").asText(isEn ? "Menu Item" : "Món ăn")
                        : item.get("itemNameI18n").toString());
                String name = nameI18n != null ? nameI18n.resolve(isEn ? "en" : "vi") : item.path("itemName").asText(isEn ? "Menu Item" : "Món ăn");
                if (name.length() > 200) name = name.substring(0, 200);
                var displayed = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("itemName", name);
                for (String field : new String[]{"quantity", "unitPrice", "lineTotal"}) {
                    if (item.path(field).isNumber()) displayed.set(field, item.get(field));
                }
                encodedLength += displayed.toString().length() + 1;
                if (displayedItems.size() == 30 || encodedLength > 8500) break;
                displayedItems.add(displayed);
            }
            variables.put("moreItems", Boolean.toString(displayedItems.size() < items.size()));
        }
        variables.put("orderItems", displayedItems.toString());
        for (String field : new String[]{"subtotal", "discountAmount", "pointsAmount", "shippingFee", "totalAmount"}) {
            if (payload.path(field).isNumber()) variables.put(field, payload.path(field).asText());
        }
        variables.put("orderType", payload.path("type").asText());
        variables.put("paymentMethod", payload.path("paymentMethod").asText());
        variables.put("paymentStatus", payload.path("paymentStatus").asText());
    }

    private String extractCustomerName(JsonNode payload, boolean isEn) {
        if (payload.hasNonNull("customerName")) {
            return payload.get("customerName").asText();
        }
        if (payload.hasNonNull("receiverName")) {
            return payload.get("receiverName").asText();
        }
        return isEn ? "Valued Customer" : "Quý khách";
    }

    private String extractAmount(JsonNode payload) {
        if (payload.hasNonNull("totalAmount")) {
            return payload.get("totalAmount").asText();
        }
        if (payload.hasNonNull("amount")) {
            return payload.get("amount").asText();
        }
        return "";
    }

    private record NotificationSpec(
            LocalizedText inAppTitle,
            LocalizedText inAppContent,
            String inAppType,
            String emailTemplate,
            Map<String, String> emailVariables
    ) {}
}

