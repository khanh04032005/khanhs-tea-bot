package com.khanh.teashop.khanhs_tea_bot.service;

import com.khanh.teashop.khanhs_tea_bot.entity.Order;
import com.khanh.teashop.khanhs_tea_bot.entity.OrderItem;
import com.khanh.teashop.khanhs_tea_bot.entity.Payment;
import com.khanh.teashop.khanhs_tea_bot.telegram.TelegramBotProperties;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderFulfillmentNotifyService {

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final TelegramNotifyService telegramNotifyService;
    private final TelegramBotProperties telegramBotProperties;

    public void notifyFulfillment(Order order, Payment payment) {
        Long internalChatId = resolveInternalChatId();
        if (internalChatId == null || order == null) {
            return;
        }

        String message = buildMessage(order, payment);
        telegramNotifyService.send(internalChatId, message);
    }

    private Long resolveInternalChatId() {
        if (telegramBotProperties.getInternalChatId() == null || telegramBotProperties.getInternalChatId().isBlank()) {
            return null;
        }

        try {
            return Long.valueOf(telegramBotProperties.getInternalChatId().trim());
        } catch (NumberFormatException exception) {
            log.warn("Invalid telegram.bot.internal-chat-id: {}", telegramBotProperties.getInternalChatId());
            return null;
        }
    }

    private String buildMessage(Order order, Payment payment) {
        StringBuilder builder = new StringBuilder();
        builder.append("🧾 PHIẾU LÀM MÓN / GIAO HÀNG\n");
        builder.append("Mã đơn: #").append(order.getId()).append("\n");
        builder.append("Khách: ").append(safe(order.getCustomerName())).append("\n");
        builder.append("SĐT: ").append(safe(order.getCustomerPhone())).append("\n");
        builder.append("Hình thức: ").append(buildDeliveryType(order)).append("\n");
        builder.append("Địa chỉ: ").append(buildAddress(order)).append("\n");
        builder.append("Trạng thái: ").append(order.getStatus() == null ? "UNKNOWN" : order.getStatus().name()).append("\n");
        builder.append("Thanh toán: ").append(payment == null ? "N/A" : payment.getStatus().name()).append("\n");
        builder.append("Tổng tiền: ").append(formatMoney(order.getTotalAmount())).append(" VND\n");
        builder.append("Thời gian: ").append(order.getCreatedAt() == null ? "N/A" : order.getCreatedAt().format(DATETIME_FORMATTER)).append("\n");
        builder.append("\nDanh sách món:\n");

        order.getItems().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(OrderItem::getId, Comparator.nullsLast(Long::compareTo)))
                .forEach(item -> builder.append("- ")
                        .append(safe(item.getProduct() == null ? null : item.getProduct().getName()))
                        .append(" | ")
                        .append(safe(item.getSize()))
                        .append(" | x")
                        .append(item.getQuantity())
                        .append(" | ")
                        .append(formatMoney(item.getLineTotal()))
                        .append(" VND\n"));

        builder.append("\nGhi chú: Chuẩn bị đơn sau khi đã xác nhận thanh toán thành công.");
        return builder.toString();
    }

    private String buildDeliveryType(Order order) {
        String address = order.getAddress();
        if (address == null || address.isBlank() || "Nhận tại quán".equalsIgnoreCase(address.trim())) {
            return "Nhận tại quán";
        }
        return "Giao hàng";
    }

    private String buildAddress(Order order) {
        String address = order.getAddress();
        if (address == null || address.isBlank()) {
            return "Nhận tại quán";
        }
        return address.trim();
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "N/A";
        }
        return value.trim();
    }

    private String formatMoney(Integer amount) {
        if (amount == null) {
            return "0";
        }
        return String.format("%,d", amount);
    }
}