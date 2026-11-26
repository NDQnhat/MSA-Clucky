package com.clucky.promotionloyaltyservice.service;

import com.clucky.promotionloyaltyservice.exception.ApiException;
import com.clucky.promotionloyaltyservice.exception.ErrorCode;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.librepdf.openpdf.fonts.Liberation;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Generates printable A4 voucher sheets using OpenPDF Liberation Unicode fonts
 * and headless ZXing QR matrices so output works identically inside Linux containers.
 */
@Service
public class VoucherPdfExportService {

    public static final int MAX_BATCH_TICKETS = 200;
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(VIETNAM_ZONE);
    private static final DecimalFormat CURRENCY_FMT = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US));

    private static final Color CLUCKY_ESPRESSO = new Color(0x16, 0x10, 0x0D);
    private static final Color CLUCKY_CREAM = new Color(0xF5, 0xED, 0xE0);
    private static final Color CLUCKY_WINE = new Color(0x8F, 0x12, 0x16);
    private static final Color CLUCKY_GOLD = new Color(0xC9, 0xA2, 0x27);
    private static final Color CLUCKY_MUTED = new Color(0x6B, 0x62, 0x59);
    private static final Color CLUCKY_BORDER = new Color(0xD6, 0xC9, 0xB9);
    private static final Color CARD_SURFACE = new Color(0xFF, 0xFA, 0xF3);

    private final byte[] logoBytes = loadLogoBytes();

    public record TicketRenderItem(int sequenceNo, String code, String qrPayload) {}

    public record BatchRenderModel(
            String batchCode,
            String campaignTitle,
            String campaignType,
            BigDecimal discountValue,
            BigDecimal minOrderAmount,
            BigDecimal maxDiscount,
            Instant expiresAt,
            Long storeId,
            String storeName,
            Instant createdAt,
            List<TicketRenderItem> tickets,
            String lang
    ) {
        public BatchRenderModel(
                String batchCode,
                String campaignTitle,
                String campaignType,
                BigDecimal discountValue,
                BigDecimal minOrderAmount,
                BigDecimal maxDiscount,
                Instant expiresAt,
                Long storeId,
                String storeName,
                Instant createdAt,
                List<TicketRenderItem> tickets
        ) {
            this(batchCode, campaignTitle, campaignType, discountValue, minOrderAmount, maxDiscount,
                    expiresAt, storeId, storeName, createdAt, tickets, "vi");
        }
    }

    public byte[] renderBatchPdf(BatchRenderModel model) {
        List<TicketRenderItem> tickets = model.tickets() == null ? List.of() : model.tickets();
        if (tickets.isEmpty() || tickets.size() > MAX_BATCH_TICKETS) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        boolean isEn = "en".equalsIgnoreCase(model.lang());

        Document document = new Document(PageSize.A4, 18, 18, 20, 20);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            PdfWriter.getInstance(document, out);
            document.open();

            Font headerTitleFont = Liberation.SANS_BOLD.create(13);
            headerTitleFont.setColor(CLUCKY_WINE);
            Font headerMetaFont = Liberation.SANS.create(9);
            headerMetaFont.setColor(CLUCKY_MUTED);

            Paragraph heading = new Paragraph(
                    sanitizeText((isEn ? "CLUCKY — COUNTER VOUCHERS (" : "CLUCKY — PHIẾU GIẢM GIÁ TẠI QUẦY (") + model.batchCode() + ")"),
                    headerTitleFont
            );
            heading.setAlignment(Element.ALIGN_LEFT);
            heading.setSpacingAfter(2f);
            document.add(heading);

            String branchLabel = model.storeId() != null
                    ? (model.storeName() != null && !model.storeName().isBlank()
                        ? model.storeName() + " (#" + model.storeId() + ")"
                        : (isEn ? "Branch #" : "Chi nhánh #") + model.storeId())
                    : (isEn ? "All Stores (HQ)" : "Toàn hệ thống (HQ)");
            String metaLine = String.format(
                    isEn
                        ? "Campaign: %s | Store: %s | Available vouchers: %d | Exp: %s (UTC+7)"
                        : "Chương trình: %s | Chi nhánh in: %s | Số phiếu khả dụng: %d | HSD: %s (UTC+7)",
                    safeTitle(model.campaignTitle(), isEn),
                    branchLabel,
                    tickets.size(),
                    model.expiresAt() != null ? TS_FMT.format(model.expiresAt()) : "—"
            );
            Paragraph meta = new Paragraph(sanitizeText(metaLine), headerMetaFont);
            meta.setSpacingAfter(8f);
            document.add(meta);

            PdfPTable grid = new PdfPTable(new float[]{1f, 1f});
            grid.setWidthPercentage(100f);

            int total = tickets.size();
            for (TicketRenderItem ticket : tickets) {
                PdfPCell wrapper = new PdfPCell();
                wrapper.setBorder(Rectangle.NO_BORDER);
                wrapper.setPadding(4f);
                wrapper.addElement(buildVoucherCard(model, ticket, total, isEn));
                grid.addCell(wrapper);
            }
            if (total % 2 != 0) {
                PdfPCell empty = new PdfPCell(new Phrase(""));
                empty.setBorder(Rectangle.NO_BORDER);
                grid.addCell(empty);
            }

            document.add(grid);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot render voucher PDF: " + ex.getMessage(), ex);
        } finally {
            if (document.isOpen()) {
                document.close();
            }
        }
        return out.toByteArray();
    }

    private PdfPTable buildVoucherCard(BatchRenderModel model, TicketRenderItem ticket, int total, boolean isEn) throws Exception {
        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100f);

        // 1. Top brand bar
        PdfPTable topBar = new PdfPTable(new float[]{1.1f, 3.2f, 2.2f});
        topBar.setWidthPercentage(100f);

        PdfPCell logoCell = new PdfPCell();
        logoCell.setBackgroundColor(CLUCKY_ESPRESSO);
        logoCell.setBorder(Rectangle.NO_BORDER);
        logoCell.setPadding(5f);
        logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        logoCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        if (logoBytes != null) {
            Image logo = Image.getInstance(logoBytes);
            logo.scaleToFit(20f, 20f);
            logoCell.addElement(logo);
        } else {
            Font brandFallback = Liberation.SANS_BOLD.create(9);
            brandFallback.setColor(CLUCKY_GOLD);
            logoCell.addElement(new Phrase("CLUCKY", brandFallback));
        }
        topBar.addCell(logoCell);

        Font brandFont = Liberation.SANS_BOLD.create(9);
        brandFont.setColor(CLUCKY_CREAM);
        PdfPCell titleCell = new PdfPCell(new Phrase(isEn ? "CLUCKY · COUNTER VOUCHER" : "CLUCKY · VOUCHER QUẦY", brandFont));
        titleCell.setBackgroundColor(CLUCKY_ESPRESSO);
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        titleCell.setPadding(5f);
        topBar.addCell(titleCell);

        Font seqFont = Liberation.SANS_BOLD.create(8);
        seqFont.setColor(CLUCKY_GOLD);
        PdfPCell seqCell = new PdfPCell(new Phrase(
                String.format("%s · %02d/%02d", model.batchCode(), ticket.sequenceNo(), total), seqFont));
        seqCell.setBackgroundColor(CLUCKY_ESPRESSO);
        seqCell.setBorder(Rectangle.NO_BORDER);
        seqCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        seqCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        seqCell.setPadding(5f);
        topBar.addCell(seqCell);

        PdfPCell topHolder = new PdfPCell(topBar);
        topHolder.setBorderColor(CLUCKY_BORDER);
        topHolder.setBorderWidth(0.8f);
        topHolder.setPadding(0f);
        card.addCell(topHolder);

        // 2. Main card body (Left: discount & terms | Right: QR code)
        PdfPTable body = new PdfPTable(new float[]{2.4f, 1.35f});
        body.setWidthPercentage(100f);

        PdfPTable details = new PdfPTable(1);
        details.setWidthPercentage(100f);

        Font discountFont = Liberation.SANS_BOLD.create(15);
        discountFont.setColor(CLUCKY_WINE);
        String discountText = "PERCENT".equalsIgnoreCase(model.campaignType())
                ? (isEn ? "SAVE " : "GIẢM ") + model.discountValue().stripTrailingZeros().toPlainString() + "%"
                : (isEn ? "SAVE " : "GIẢM ") + CURRENCY_FMT.format(model.discountValue()) + " đ";
        PdfPCell discountCell = new PdfPCell(new Phrase(discountText, discountFont));
        discountCell.setBorder(Rectangle.NO_BORDER);
        discountCell.setPadding(2f);
        details.addCell(discountCell);

        Font campaignFont = Liberation.SANS_BOLD.create(9);
        campaignFont.setColor(CLUCKY_ESPRESSO);
        PdfPCell campaignCell = new PdfPCell(new Phrase(sanitizeText(safeTitle(model.campaignTitle(), isEn)), campaignFont));
        campaignCell.setBorder(Rectangle.NO_BORDER);
        campaignCell.setPadding(2f);
        details.addCell(campaignCell);

        Font termFont = Liberation.SANS.create(8);
        termFont.setColor(CLUCKY_MUTED);
        String minOrderText = model.minOrderAmount() != null && model.minOrderAmount().signum() > 0
                ? CURRENCY_FMT.format(model.minOrderAmount()) + " đ"
                : "0 đ";
        String maxDiscountText = "PERCENT".equalsIgnoreCase(model.campaignType())
                && model.maxDiscount() != null && model.maxDiscount().signum() > 0
                ? (isEn ? " · Max " : " · Tối đa ") + CURRENCY_FMT.format(model.maxDiscount()) + " đ"
                : "";
        PdfPCell minCell = new PdfPCell(new Phrase(
                sanitizeText((isEn ? "Min order: " : "Đơn tối thiểu: ") + minOrderText + maxDiscountText), termFont));
        minCell.setBorder(Rectangle.NO_BORDER);
        minCell.setPadding(1.5f);
        details.addCell(minCell);

        String scopeText = model.storeId() != null
                ? (isEn ? "Valid at: " : "Áp dụng tại: ") + (model.storeName() != null && !model.storeName().isBlank()
                    ? model.storeName() : (isEn ? "Branch #" : "Chi nhánh #") + model.storeId())
                : (isEn ? "Valid at: Eligible stores" : "Áp dụng: Các chi nhánh hợp lệ");
        PdfPCell scopeCell = new PdfPCell(new Phrase(sanitizeText(scopeText), termFont));
        scopeCell.setBorder(Rectangle.NO_BORDER);
        scopeCell.setPadding(1.5f);
        details.addCell(scopeCell);

        PdfPCell expiryCell = new PdfPCell(new Phrase(
                (isEn ? "Exp: " : "HSD: ") + (model.expiresAt() != null ? TS_FMT.format(model.expiresAt()) : "—"), termFont));
        expiryCell.setBorder(Rectangle.NO_BORDER);
        expiryCell.setPadding(1.5f);
        details.addCell(expiryCell);

        PdfPCell leftCell = new PdfPCell(details);
        leftCell.setBorder(Rectangle.NO_BORDER);
        leftCell.setBackgroundColor(CARD_SURFACE);
        leftCell.setPadding(6f);
        leftCell.setVerticalAlignment(Element.ALIGN_TOP);
        body.addCell(leftCell);

        // Right column: QR + channel tag
        PdfPTable qrBox = new PdfPTable(1);
        qrBox.setWidthPercentage(100f);
        Image qrImage = generateQrImage(ticket.qrPayload(), 110);
        qrImage.scaleToFit(74f, 74f);
        PdfPCell qrImgCell = new PdfPCell(qrImage);
        qrImgCell.setBorder(Rectangle.NO_BORDER);
        qrImgCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        qrImgCell.setPadding(2f);
        qrBox.addCell(qrImgCell);

        Font channelFont = Liberation.SANS_BOLD.create(7);
        channelFont.setColor(CLUCKY_WINE);
        PdfPCell channelCell = new PdfPCell(new Phrase(isEn ? "COUNTER ONLY" : "CHỈ DÙNG TẠI QUẦY", channelFont));
        channelCell.setBorder(Rectangle.NO_BORDER);
        channelCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        channelCell.setPaddingTop(1f);
        qrBox.addCell(channelCell);

        PdfPCell rightCell = new PdfPCell(qrBox);
        rightCell.setBorder(Rectangle.NO_BORDER);
        rightCell.setBackgroundColor(CARD_SURFACE);
        rightCell.setPadding(4f);
        rightCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        body.addCell(rightCell);

        PdfPCell bodyHolder = new PdfPCell(body);
        bodyHolder.setBorderColor(CLUCKY_BORDER);
        bodyHolder.setBorderWidth(0.8f);
        bodyHolder.setPadding(0f);
        card.addCell(bodyHolder);

        // 3. Footer fallback text code
        Font codeLabelFont = Liberation.SANS.create(7);
        codeLabelFont.setColor(CLUCKY_MUTED);
        Font codeFont = Liberation.SANS_BOLD.create(7);
        codeFont.setColor(CLUCKY_ESPRESSO);
        Phrase footerPhrase = new Phrase();
        footerPhrase.add(new Phrase(isEn ? "Backup code: " : "Mã dự phòng: ", codeLabelFont));
        footerPhrase.add(new Phrase(ticket.code(), codeFont));

        PdfPCell footerCell = new PdfPCell(footerPhrase);
        footerCell.setBackgroundColor(CLUCKY_CREAM);
        footerCell.setBorderColor(CLUCKY_BORDER);
        footerCell.setBorderWidth(0.8f);
        footerCell.setPadding(4.5f);
        footerCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        card.addCell(footerCell);

        return card;
    }

    private Image generateQrImage(String payload, int pixels) throws Exception {
        QRCodeWriter writer = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = Map.of(
                EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN, 1
        );
        BitMatrix matrix = writer.encode(payload, BarcodeFormat.QR_CODE, pixels, pixels, hints);
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        byte[] gray = new byte[width * height];
        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                gray[rowOffset + x] = matrix.get(x, y) ? (byte) 0x00 : (byte) 0xFF;
            }
        }
        return Image.getInstance(width, height, 1, 8, gray);
    }

    private static byte[] loadLogoBytes() {
        try {
            ClassPathResource resource = new ClassPathResource("assets/logo_primary.png");
            if (!resource.exists()) {
                return null;
            }
            try (InputStream in = resource.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safeTitle(String title, boolean isEn) {
        if (title == null || title.isBlank()) {
            return isEn ? "Clucky Promotion" : "Ưu đãi Clucky";
        }
        String trimmed = title.strip();
        return trimmed.length() > 62 ? trimmed.substring(0, 59) + "..." : trimmed;
    }

    private static String sanitizeText(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String trimmed = input.stripLeading();
        char first = input.charAt(0);
        char nonSpace = trimmed.isEmpty() ? first : trimmed.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r'
                || nonSpace == '=' || nonSpace == '+' || nonSpace == '-' || nonSpace == '@') {
            return "'" + input;
        }
        return input;
    }
}
