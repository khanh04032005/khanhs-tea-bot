package com.khanh.teashop.khanhs_tea_bot.telegram;

import com.khanh.teashop.khanhs_tea_bot.dto.CreateOrderItemRequest;
import com.khanh.teashop.khanhs_tea_bot.dto.CreateOrderRequest;
import com.khanh.teashop.khanhs_tea_bot.dto.OrderResponse;
import com.khanh.teashop.khanhs_tea_bot.dto.gemini.IntentResult;
import com.khanh.teashop.khanhs_tea_bot.dto.payment.PaymentResponse;
import com.khanh.teashop.khanhs_tea_bot.entity.Product;
import com.khanh.teashop.khanhs_tea_bot.service.*;

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
            // --- 1. CÁC LỆNH COMMAND CỨNG (BẮT ĐẦU BẰNG /) ---
            if ("/start".equalsIgnoreCase(text)) {
                send(chatId, """
            Xin chào, mình là TeaShop Assistant Bot 👋
            Lệnh:
            /menu - Xem thực đơn
            /add <mã> <size M|L> <số lượng>
            /cart - Xem giỏ hàng
            /checkout <tên> <sđt> [địa chỉ]
            /clear - Xóa giỏ hàng
            /cancel <mã_đơn>

            💡 Lưu ý:
            - Phần [địa chỉ] là tùy chọn. Nếu cần giao hàng hãy nhập địa chỉ sau SĐT.
            - Nếu bỏ trống địa chỉ, đơn sẽ được chuẩn bị để nhận tại cửa hàng.
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

            // --- 2. XỬ LÝ NGÔN NGỮ TỰ NHIÊN (KHÔNG DÙNG /) ---
            String lower = text.toLowerCase();

            // Sửa lỗi "hư menu": Chặn từ khóa trước khi trôi xuống RAG
            if (lower.equals("menu") || lower.contains("xem menu") || lower.contains("cho xem menu") || lower.contains("thực đơn")) {
                send(chatId, buildMenuText());
                return;
            }

            if (lower.contains("mở cửa") || lower.contains("giờ") || lower.contains("mấy giờ")) {
                send(chatId, "Quán mở cửa từ 8:00 đến 22:00 mỗi ngày.");
                return;
            }

            if (lower.contains("giỏ") || lower.contains("cart")) {
                send(chatId, buildCartText(chatId));
                return;
            }

            if (tryHandleNaturalAdd(chatId, text)) {
                return;
            }

            // --- 3. XỬ LÝ QUA AI (GEMINI INTENT) ---
            IntentResult intent = geminiIntentService.parseIntent(text, productService.getAllProducts());

            if ("SHOW_MENU".equalsIgnoreCase(intent.getIntent())) {
                send(chatId, buildMenuText());
                return;
            }

            if ("SHOW_CART".equalsIgnoreCase(intent.getIntent())) {
                send(chatId, buildCartText(chatId));
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
                String addr = (intent.getAddress() != null) ? " " + intent.getAddress() : "";
                handleCheckout(chatId, "/checkout " + intent.getCustomerName() + " " + intent.getCustomerPhone() + addr);
                return;
            }

            // --- 4. CUỐI CÙNG MỚI LÀ RAG (HỎI ĐÁP LINH TINH) ---
            String ragAnswer = ragService.answerMenuQuestion(text);
            if (ragAnswer != null && !ragAnswer.isBlank()) {
                send(chatId, ragAnswer);
                return;
            }

            send(chatId, "Mục này mình chưa rõ. Bạn dùng /start để xem các lệnh hỗ trợ nhé.");
        } catch (ResponseStatusException exception) {
            send(chatId, "Lỗi: " + exception.getReason());
        } catch (Exception exception) {
            log.error("Bot error, text={}", text, exception);
            send(chatId, "Có lỗi xảy ra, bạn thử lại giúp mình.");
        }
    }

    // --- LOGIC THANH TOÁN (CHECKOUT) ---
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
            // Tách làm 4 phần: /checkout | tên | sđt | địa chỉ (nếu có)
            String[] parts = text.split("\\s+", 4);
            if (parts.length < 3) {
                send(chatId, "Sai cú pháp. Dùng: /checkout <tên> <sđt> [địa chỉ]");
                return;
            }

            String customerName = parts[1].trim();
            String customerPhone = parts[2].trim();
            String address = (parts.length == 4) ? parts[3].trim() : "Nhận tại cửa hàng";

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
                    .address(address)
                    .telegramChatId(chatId)
                    .items(items)
                    .build();

            OrderResponse response = orderService.createOrder(request);
            PaymentResponse payment = payOsService.createPaymentForOrder(response.getId());

            carts.remove(chatId);
            cartTouchedAt.remove(chatId);

            send(chatId, """
                Tạo đơn thành công ✅
                Mã đơn: #%d
                Khách hàng: %s
                Địa chỉ: %s
                Tổng tiền: %,d VND

                Bấm vào link bên dưới để thanh toán:
                %s
                """.formatted(
                    response.getId(),
                    customerName,
                    address,
                    response.getTotalAmount(),
                    payment.getCheckoutUrl()
            ));

            if (payment.getQrCode() != null && !payment.getQrCode().isBlank()) {
                sendQrImage(chatId, payment.getQrCode());
            }
        } finally {
            releaseCheckoutLock(chatId);
        }
    }

    private void handleAdd(Long chatId, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length != 4) {
            send(chatId, "Sai cú pháp. Dùng: /add <mã> <size> <số lượng>");
            return;
        }

        String productId = parts[1].trim().toUpperCase();
        String size = parts[2].trim().toUpperCase();
        int quantity;
        try {
            quantity = Integer.parseInt(parts[3].trim());
        } catch (Exception e) {
            send(chatId, "Số lượng phải là số.");
            return;
        }

        if (quantity <= 0 || quantity > MAX_QTY_PER_ITEM) {
            send(chatId, "Số lượng từ 1 đến " + MAX_QTY_PER_ITEM);
            return;
        }

        Product product = productService.getProductById(productId);
        if (!product.isAvailable()) {
            send(chatId, "Món này hiện đang hết hàng.");
            return;
        }

        List<CartItem> cart = carts.computeIfAbsent(chatId, k -> new ArrayList<>());
        cart.removeIf(i -> i.productId().equals(productId) && i.size().equals(size));
        int price = "M".equals(size) ? product.getPriceM() : product.getPriceL();
        cart.add(new CartItem(product.getId(), product.getName(), size, quantity, price));

        touchCart(chatId);
        send(chatId, "Đã thêm " + product.getName() + " (" + size + ") x" + quantity);
    }

    // --- CÁC HÀM BỔ TRỢ ---
    private String buildMenuText() {
        List<Product> products = productService.getAllProducts();
        StringBuilder sb = new StringBuilder("🧋 THỰC ĐƠN CỦA QUÁN\n");
        products.stream().filter(Product::isAvailable)
                .sorted(Comparator.comparing(Product::getCategory))
                .forEach(p -> sb.append(String.format("• %s - %s: %s (M:%d, L:%d)\n", p.getCategory(), p.getId(), p.getName(), p.getPriceM(), p.getPriceL())));
        return sb.toString();
    }

    private String buildCartText(Long chatId) {
        List<CartItem> cart = carts.getOrDefault(chatId, List.of());
        if (cart.isEmpty()) return "Giỏ hàng trống.";
        StringBuilder sb = new StringBuilder("🛒 GIỎ HÀNG:\n");
        int total = 0;
        for (CartItem item : cart) {
            int sub = item.unitPrice() * item.quantity();
            total += sub;
            sb.append(String.format("- %s (%s) x%d: %,dđ\n", item.productName(), item.size(), item.quantity(), sub));
        }
        sb.append("Tổng: ").append(String.format("%,dđ", total));
        return sb.toString();
    }

    private void handleRemove(Long chatId, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length < 3) return;
        List<CartItem> cart = carts.get(chatId);
        if (cart != null) {
            cart.removeIf(i -> i.productId().equalsIgnoreCase(parts[1]) && i.size().equalsIgnoreCase(parts[2]));
            send(chatId, "Đã xóa món khỏi giỏ.");
        }
    }

    private void handleCancel(Long chatId, String text) {
        try {
            Long orderId = Long.parseLong(text.split("\\s+")[1]);
            orderService.cancelOrder(orderId);
            send(chatId, "Đã hủy đơn #" + orderId);
        } catch (Exception e) {
            send(chatId, "Không tìm thấy mã đơn.");
        }
    }

    private void send(Long chatId, String message) {
        try {
            execute(SendMessage.builder().chatId(chatId.toString()).text(message).build());
        } catch (TelegramApiException e) {
            log.error("Send failed", e);
        }
    }

    private void sendQrImage(Long chatId, String qrRaw) {
        try {
            String url = "https://quickchart.io/qr?size=320&text=" + java.net.URLEncoder.encode(qrRaw, "UTF-8");
            execute(org.telegram.telegrambots.meta.api.methods.send.SendPhoto.builder()
                    .chatId(chatId.toString())
                    .photo(new org.telegram.telegrambots.meta.api.objects.InputFile(url))
                    .caption("Quét mã để thanh toán nhanh")
                    .build());
        } catch (Exception e) {
            log.error("QR send failed", e);
        }
    }

    private boolean tryHandleNaturalAdd(Long chatId, String text) {
        Pattern p = Pattern.compile("(?i)(?:thêm|add)\\s+(\\d+)\\s+([a-z]{2,5}\\d{2})\\s+size\\s+([ml])");
        Matcher m = p.matcher(text);
        if (m.find()) {
            handleAdd(chatId, "/add " + m.group(2) + " " + m.group(3) + " " + m.group(1));
            return true;
        }
        return false;
    }

    private boolean allowRequest(Long chatId) {
        long now = System.currentTimeMillis();
        Long last = lastRequestAt.put(chatId, now);
        return last == null || (now - last) >= REQUEST_COOLDOWN_MS;
    }

    private void touchCart(Long chatId) { cartTouchedAt.put(chatId, System.currentTimeMillis()); }

    private void clearExpiredCartIfNeeded(Long chatId) {
        Long last = cartTouchedAt.get(chatId);
        if (last != null && (System.currentTimeMillis() - last > CART_TIMEOUT_MS)) {
            carts.remove(chatId);
            cartTouchedAt.remove(chatId);
        }
    }

    private boolean acquireCheckoutLock(Long chatId) { return checkoutLocks.putIfAbsent(chatId, true) == null; }
    private void releaseCheckoutLock(Long chatId) { checkoutLocks.remove(chatId); }

    private record CartItem(String productId, String productName, String size, int quantity, int unitPrice) {}
}