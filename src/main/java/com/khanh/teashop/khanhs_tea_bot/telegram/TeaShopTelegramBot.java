package com.khanh.teashop.khanhs_tea_bot.telegram;

import com.khanh.teashop.khanhs_tea_bot.dto.CreateOrderItemRequest;
import com.khanh.teashop.khanhs_tea_bot.dto.CreateOrderRequest;
import com.khanh.teashop.khanhs_tea_bot.dto.OrderResponse;
import com.khanh.teashop.khanhs_tea_bot.dto.gemini.IntentResult;
import com.khanh.teashop.khanhs_tea_bot.dto.payment.PaymentResponse;
import com.khanh.teashop.khanhs_tea_bot.entity.Product;
import com.khanh.teashop.khanhs_tea_bot.service.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Component
@Slf4j
@RequiredArgsConstructor
public class TeaShopTelegramBot extends TelegramLongPollingBot {

    private static final long REQUEST_COOLDOWN_MS = 700;
    private static final long CART_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final int MAX_QTY_PER_ITEM = 20;

    private final ProductService productService;
    private final OrderService orderService;
    private final TelegramBotProperties telegramBotProperties;
    private final GeminiIntentService geminiIntentService;
    private final RagService ragService;
    private final PayOsService payOsService;

    private final Map<Long, List<CartItem>> carts = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastRequestAt = new ConcurrentHashMap<>();
    private final Map<Long, Long> cartTouchedAt = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> checkoutLocks = new ConcurrentHashMap<>();

    @Override
    public String getBotUsername() {
        return telegramBotProperties.getUsername();
    }

    @Override
    public String getBotToken() {
        return telegramBotProperties.getToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update == null || !update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String text = update.getMessage().getText().trim();
        Long chatId = update.getMessage().getChatId();

        if (text.length() > MAX_MESSAGE_LENGTH) {
            send(chatId, "Tin nhắn quá dài, vui lòng nhập ngắn gọn hơn.");
            return;
        }

        if (!allowRequest(chatId)) {
            send(chatId, "Bạn thao tác hơi nhanh, chờ 1 chút rồi thử lại nhé.");
            return;
        }

        clearExpiredCartIfNeeded(chatId);

        try {
            if ("/start".equalsIgnoreCase(text)) {
                send(chatId, """
        Xin chào! Mình là TeaShop Assistant Bot 🍵
        Rất vui được phục vụ bạn!
        
        📌 CÁC LỆNH CHÍNH:
        /menu : Xem danh sách món ăn & topping.
        /add <mã> <size> <số_lượng> ... : Thêm một hoặc NHIỀU món cùng lúc.
        👉 Ví dụ: /add TS01 M 2 thêm TOP01 M 1 (Thêm 2 trà sữa và 1 topping).
        
        /cart : Xem giỏ hiện tại.
        /remove <mã> <size> : Xóa món khỏi giỏ.
        /clear : Xóa toàn bộ giỏ hàng.
        
        /checkout <tên> <sđt> [địa chỉ] : Đặt hàng & Thanh toán.
        /cancel <mã_đơn> : Hủy đơn hàng đã đặt.        
        Sau khi /checkout, mình sẽ gửi Link và mã QR thanh toán. Khi bạn chuyển khoản thành công, mình sẽ tự động báo trạng thái đơn hàng ngay! 🚀
        """);
                return;
            }

            if ("/menu".equalsIgnoreCase(text)) {
                send(chatId, buildMenuText());
                return;
            }

            if (text.toLowerCase().startsWith("/add ")) {
                handleAdd(chatId, text);
                return;
            }

            if (text.toLowerCase().startsWith("/remove ")) {
                handleRemove(chatId, text);
                return;
            }

            if ("/cart".equalsIgnoreCase(text)) {
                send(chatId, buildCartText(chatId));
                return;
            }

            if (text.toLowerCase().startsWith("/checkout ")) {
                handleCheckout(chatId, text);
                return;
            }

            if (text.toLowerCase().startsWith("/cancel ")) {
                handleCancel(chatId, text);
                return;
            }

            if ("/clear".equalsIgnoreCase(text)) {
                carts.remove(chatId);
                cartTouchedAt.remove(chatId);
                send(chatId, "Đã xóa giỏ hàng.");
                return;
            }

            // Natural language add
            if (tryHandleNaturalAdd(chatId, text)) {
                return;
            }

            // LLM Intent Analysis
            IntentResult intent = geminiIntentService.parseIntent(text, productService.getAllProducts());

            if ("SHOW_MENU".equalsIgnoreCase(intent.getIntent())) {
                send(chatId, buildMenuText());
                return;
            }

            if ("SHOW_CART".equalsIgnoreCase(intent.getIntent())) {
                send(chatId, buildCartText(chatId));
                return;
            }

            if ("CLEAR".equalsIgnoreCase(intent.getIntent())) {
                carts.remove(chatId);
                cartTouchedAt.remove(chatId);
                send(chatId, "Đã xóa giỏ hàng.");
                return;
            }

            if ("ADD_ITEM".equalsIgnoreCase(intent.getIntent())
                    && intent.getProductId() != null
                    && intent.getSize() != null
                    && intent.getQuantity() != null) {
                handleAdd(chatId, "/add " + intent.getProductId() + " " + intent.getSize() + " " + intent.getQuantity());
                return;
            }

            if ("CHECKOUT".equalsIgnoreCase(intent.getIntent())
                    && intent.getCustomerName() != null
                    && intent.getCustomerPhone() != null) {
                handleCheckout(chatId, "/checkout " + intent.getCustomerName() + " " + intent.getCustomerPhone());
                return;
            }

            // Product ID pattern (TS01, CF02, etc.)
            String upper = text.toUpperCase();
            Matcher itemMatcher = Pattern.compile("\\b([A-Z]{2,5}\\d{2})\\b").matcher(upper);
            if (itemMatcher.find()) {
                String itemId = itemMatcher.group(1);
                Product p = productService.getProductById(itemId);
                send(chatId, p.getId() + " - " + p.getName()
                        + "\nGiá M: " + p.getPriceM()
                        + "\nGiá L: " + p.getPriceL()
                        + "\nCòn bán: " + (p.isAvailable() ? "Có" : "Không"));
                return;
            }

            // RAG Q&A
            String ragAnswer = ragService.answerMenuQuestion(text);
            if (ragAnswer != null && !ragAnswer.isBlank()) {
                send(chatId, ragAnswer);
                return;
            }

            // Freelance LLM answer cho câu hỏi ngoài chủ đề
            String llmReply = geminiIntentService.answerFreelance(text);
            if (llmReply != null && !llmReply.isBlank()) {
                send(chatId, llmReply);
                return;
            }

            send(chatId, "Mình chưa hiểu. Dùng /start để xem lệnh.");
        } catch (ResponseStatusException exception) {
            send(chatId, "Lỗi: " + exception.getReason());
        } catch (Exception exception) {
            log.error("Bot error, text={}", text, exception);
            send(chatId, "Có lỗi xảy ra, bạn thử lại giúp mình.");
        }
    }

    private boolean tryHandleNaturalAdd(Long chatId, String text) {
        String input = text.trim();

        Pattern p1 = Pattern.compile("(?i).*(thêm|them|add)\\s+(\\d+)\\s+([a-z]{2,5}\\d{2})\\s*(?:size\\s*)?([ml]).*");
        Matcher m1 = p1.matcher(input);
        if (m1.matches()) {
            handleAdd(chatId, "/add " + m1.group(3).toUpperCase() + " " + m1.group(4).toUpperCase() + " " + m1.group(2));
            return true;
        }

        Pattern p2 = Pattern.compile("(?i).*(thêm|them|add)\\s+([a-z]{2,5}\\d{2})\\s*(?:size\\s*)?([ml])\\s+(\\d+).*");
        Matcher m2 = p2.matcher(input);
        if (m2.matches()) {
            handleAdd(chatId, "/add " + m2.group(2).toUpperCase() + " " + m2.group(3).toUpperCase() + " " + m2.group(4));
            return true;
        }

        return false;
    }

    private void handleAdd(Long chatId, String text) {
        // Chuẩn hóa chuỗi: thay dấu phẩy bằng khoảng trắng, chuyển thành chữ hoa
        String cleanText = text.replace(",", " ").toUpperCase();
        String[] parts = cleanText.split("\\s+");

        StringBuilder successReport = new StringBuilder("✅ Đã thêm vào giỏ hàng:\n");

        // i bắt đầu từ 1 để bỏ qua chữ "/add"
        int i = 1;
        while (i < parts.length) {
            // 1. Xử lý món chính
            String productId = parts[i];
            String size = (i + 1 < parts.length) ? parts[i + 1] : "M";
            int quantity = (i + 2 < parts.length) ? Integer.parseInt(parts[i + 2]) : 1;

            Product product = productService.getProductById(productId);
            addToCartLogic(chatId, product, size, quantity); // Hàm phụ gọi logic lưu vào Map carts

            successReport.append("✨ ").append(product.getName())
                    .append(" (").append(size).append(") x").append(quantity);

            i += 3; // Nhảy qua bộ 3 (ID, Size, Qty)

            // 2. Kiểm tra nếu có từ khóa "THÊM" để xử lý Topping đi kèm
            if (i < parts.length && "THÊM".equals(parts[i])) {
                i++; // Bỏ qua chữ "THÊM"

                if (i + 2 < parts.length) {
                    String topId = parts[i];
                    String topSize = parts[i + 1];
                    int topQty = Integer.parseInt(parts[i + 2]);

                    Product topping = productService.getProductById(topId);
                    addToCartLogic(chatId, topping, topSize, topQty);

                    successReport.append(" thêm ").append(topping.getName())
                            .append(" ").append(topSize).append(" x").append(topQty);
                    i += 3;
                }
            }
            successReport.append("\n");

            // Nếu sau đó là dấu cách hoặc ký tự khác không phải "THÊM", vòng lặp tiếp tục món mới
        }

        touchCart(chatId);
        send(chatId, successReport.toString().trim());
    }

    private void addToCartLogic(Long chatId, Product product, String size, int quantity) {
        List<CartItem> cart = carts.computeIfAbsent(chatId, key -> new ArrayList<>());
        CartItem existing = cart.stream()
                .filter(item -> item.productId().equals(product.getId()) && item.size().equals(size))
                .findFirst().orElse(null);

        if (existing != null) {
            cart.remove(existing);
            cart.add(new CartItem(existing.productId(), existing.productName(), size, existing.quantity() + quantity, existing.unitPrice()));
        } else {
            int price = "M".equals(size) ? product.getPriceM() : product.getPriceL();
            cart.add(new CartItem(product.getId(), product.getName(), size, quantity, price));
        }
    }

    private void handleRemove(Long chatId, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length != 3) {
            send(chatId, "Sai cú pháp. Dùng: /remove <productId> <M|L>");
            return;
        }

        String productId = parts[1].trim().toUpperCase();
        String size = parts[2].trim().toUpperCase();

        List<CartItem> cart = carts.getOrDefault(chatId, new ArrayList<>());
        CartItem target = cart.stream()
                .filter(item -> item.productId().equals(productId) && item.size().equals(size))
                .findFirst()
                .orElse(null);

        if (target == null) {
            send(chatId, "Không tìm thấy món trong giỏ.");
            return;
        }

        cart.remove(target);
        if (cart.isEmpty()) {
            carts.remove(chatId);
            cartTouchedAt.remove(chatId);
        } else {
            carts.put(chatId, cart);
            touchCart(chatId);
        }

        send(chatId, "Đã xóa " + productId + " size " + size + " khỏi giỏ.");
    }

    private void handleCheckout(Long chatId, String text) {
        List<CartItem> cart = carts.getOrDefault(chatId, List.of());
        if (cart.isEmpty()) {
            send(chatId, "Giỏ hàng đang trống. Dùng /add để thêm món.");
            return;
        }

        if (!acquireCheckoutLock(chatId)) {
            send(chatId, "Đơn đang được xử lý, vui lòng chờ...");
            return;
        }

        try {
            String[] parts = text.split("\\s+", 4);
            if (parts.length < 3) {
                send(chatId, "Sai cú pháp. Dùng: /checkout <ten_khach> <so_dien_thoai> [dia_chi]");
                return;
            }

            String customerName = parts[1].trim();
            String customerPhone = parts[2].trim();
            String address = (parts.length > 3) ? parts[3].trim() : null;

            if (!customerPhone.matches("^[0-9+]{9,15}$")) {
                send(chatId, "Số điện thoại không hợp lệ.");
                return;
            }

            List<CreateOrderItemRequest> items = cart.stream()
                    .map(item -> CreateOrderItemRequest.builder()
                            .productId(item.productId())
                            .size(item.size())
                            .quantity(item.quantity())
                            .build())
                    .toList();

            CreateOrderRequest request = CreateOrderRequest.builder()
                    .customerName(customerName)
                    .customerPhone(customerPhone)
                    .address(address != null ? address : "Nhận tại quán")
                    .telegramChatId(chatId)
                    .items(items)
                    .build();

            OrderResponse response = orderService.createOrder(request);
            PaymentResponse payment = payOsService.createPaymentForOrder(response.getId());

            carts.remove(chatId);
            cartTouchedAt.remove(chatId);

            String deliveryInfo = address != null
                    ? "📍 Ship tới: " + address
                    : "📍 Nhận tại quán";

            send(chatId, """
                Tạo đơn thành công ✅
                Mã đơn: %d
                Tổng tiền: %,d VND
                Trạng thái: %s
                %s

                Quét QR bên dưới hoặc bấm link thanh toán:
                %s
                """.formatted(
                    response.getId(),
                    response.getTotalAmount(),
                    response.getStatus(),
                    deliveryInfo,
                    payment.getCheckoutUrl()
            ));

            if (payment.getQrCode() != null && !payment.getQrCode().isBlank()) {
                sendQrImage(chatId, payment.getQrCode());
            } else {
                send(chatId, "Hiện chưa tạo được mã QR, vui lòng thanh toán bằng link ở trên.");
            }
        } finally {
            releaseCheckoutLock(chatId);
        }
    }

    private void handleCancel(Long chatId, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length != 2) {
            send(chatId, "Sai cú pháp. Dùng: /cancel <orderId>");
            return;
        }

        Long orderId;
        try {
            orderId = Long.parseLong(parts[1].trim());
        } catch (NumberFormatException exception) {
            send(chatId, "orderId phải là số.");
            return;
        }

        OrderResponse response = orderService.cancelOrder(orderId);
        send(chatId, "Đơn " + response.getId() + " đã chuyển sang CANCELLED.");
    }

    private String buildMenuText() {
        List<Product> products = productService.getAllProducts().stream()
                .filter(Product::isAvailable)
                .sorted(Comparator.comparing(Product::getCategory).thenComparing(Product::getId))
                .toList();

        StringBuilder builder = new StringBuilder("🧋 MENU\n");
        String currentCategory = "";

        for (Product product : products) {
            if (!product.getCategory().equals(currentCategory)) {
                currentCategory = product.getCategory();
                builder.append("\n").append(currentCategory).append("\n");
            }
            builder.append("• ").append(product.getId()).append(" - ")
                    .append(product.getName())
                    .append(" (M: ").append(product.getPriceM())
                    .append(", L: ").append(product.getPriceL()).append(")\n");
        }

        builder.append("\nDùng: /add TS01 M 2");
        return builder.toString();
    }

    private String buildCartText(Long chatId) {
        List<CartItem> cart = carts.getOrDefault(chatId, List.of());
        if (cart.isEmpty()) {
            return "Giỏ hàng đang trống.";
        }

        int total = 0;
        StringBuilder builder = new StringBuilder("GIỎ HÀNG:\n");
        for (CartItem item : cart) {
            int lineTotal = item.unitPrice() * item.quantity();
            total += lineTotal;
            builder.append(item.productId())
                    .append(" | ")
                    .append(item.productName())
                    .append(" | ")
                    .append(item.size())
                    .append(" | x")
                    .append(item.quantity())
                    .append(" | ")
                    .append(lineTotal)
                    .append(" VND\n");
        }
        builder.append("Tổng: ").append(total).append(" VND");
        return builder.toString();
    }

    private void send(Long chatId, String message) {
        try {
            execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(message)
                    .build());
        } catch (TelegramApiException exception) {
            log.error("Failed to send message", exception);
        }
    }

    private boolean allowRequest(Long chatId) {
        long now = System.currentTimeMillis();
        Long last = lastRequestAt.put(chatId, now);
        return last == null || (now - last) >= REQUEST_COOLDOWN_MS;
    }

    private void touchCart(Long chatId) {
        cartTouchedAt.put(chatId, System.currentTimeMillis());
    }

    private void clearExpiredCartIfNeeded(Long chatId) {
        Long touched = cartTouchedAt.get(chatId);
        if (touched == null) {
            return;
        }
        if (System.currentTimeMillis() - touched > CART_TIMEOUT_MS) {
            carts.remove(chatId);
            cartTouchedAt.remove(chatId);
        }
    }

    private void sendQrImage(Long chatId, String qrRaw) {
        try {
            String qrImageUrl = "https://quickchart.io/qr?size=320&text="
                    + java.net.URLEncoder.encode(qrRaw, java.nio.charset.StandardCharsets.UTF_8);

            execute(org.telegram.telegrambots.meta.api.methods.send.SendPhoto.builder()
                    .chatId(chatId.toString())
                    .photo(new org.telegram.telegrambots.meta.api.objects.InputFile(qrImageUrl))
                    .caption("Quét mã QR để thanh toán")
                    .build());
        } catch (Exception exception) {
            log.error("Failed to send QR image", exception);
        }
    }

    private boolean acquireCheckoutLock(Long chatId) {
        return checkoutLocks.putIfAbsent(chatId, true) == null;
    }

    private void releaseCheckoutLock(Long chatId) {
        checkoutLocks.remove(chatId);
    }

    private record CartItem(String productId, String productName, String size, int quantity, int unitPrice) {
    }
}