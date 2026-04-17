package com.khanh.teashop.khanhs_tea_bot.telegram;

import com.khanh.teashop.khanhs_tea_bot.dto.CreateOrderItemRequest;
import com.khanh.teashop.khanhs_tea_bot.dto.CreateOrderRequest;
import com.khanh.teashop.khanhs_tea_bot.dto.OrderResponse;
import com.khanh.teashop.khanhs_tea_bot.dto.gemini.IntentResult;
import com.khanh.teashop.khanhs_tea_bot.dto.payment.PaymentResponse;
import com.khanh.teashop.khanhs_tea_bot.entity.Product;
import com.khanh.teashop.khanhs_tea_bot.service.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
    public String getBotUsername() { return telegramBotProperties.getUsername(); }

    @Override
    public String getBotToken() { return telegramBotProperties.getToken(); }

    @Override
    public void onUpdateReceived(Update update) {
        if (update == null || !update.hasMessage() || !update.getMessage().hasText()) return;

        String text = update.getMessage().getText().trim();
        String lowerText = text.toLowerCase();
        Long chatId = update.getMessage().getChatId();

        if (!allowRequest(chatId)) return;
        clearExpiredCartIfNeeded(chatId);

        try {
            // --- BƯỚC 1: KIỂM TRA TỪ KHÓA CỨNG (ĂN NGAY KHÔNG CẦN AI) ---

            // Nhắc menu là hiện menu
            if (lowerText.contains("menu") || lowerText.contains("thực đơn") || lowerText.contains("xem món")) {
                send(chatId, buildMenuText());
                return;
            }

            // Nhắc thanh toán/checkout là hiện QR/Checkout
            if (lowerText.contains("thanh toán") || lowerText.contains("tính tiền")
                    || lowerText.contains("checkout") || lowerText.contains("trả tiền")) {

                // Nếu khách chỉ nói mỗi "thanh toán", mình nhắc họ nhập thông tin
                if (lowerText.equals("thanh toán") || lowerText.equals("tính tiền") || lowerText.equals("checkout")) {
                    send(chatId, "Bạn vui lòng nhập: Tên + SĐT + Địa chỉ để mình gửi link thanh toán nha!");
                    return;
                }
                // Nếu có kèm thông tin khác, để AI xử lý tiếp ở dưới
            }

            if (text.startsWith("/")) {
                handleSystemCommands(chatId, text);
                return;
            }

            // --- BƯỚC 2: GỌI AI ĐỂ XỬ LÝ CÁC CÂU PHỨC TẠP ---
            IntentResult intent = geminiIntentService.parseIntent(text, productService.getAllProducts());
            String intentStr = (intent.getIntent() != null) ? intent.getIntent().toUpperCase() : "CHITCHAT";

            switch (intentStr) {
                case "ADD_ITEM":
                    if (intent.getProductId() != null) {
                        String sz = (intent.getSize() != null) ? intent.getSize().toUpperCase() : "M";
                        int q = (intent.getQuantity() != null && intent.getQuantity() > 0) ? intent.getQuantity() : 1;
                        handleAdd(chatId, "/add " + intent.getProductId() + " " + sz + " " + q);
                    } else {
                        send(chatId, "Bạn muốn thêm món nào nè? Nhắn mã món (VD: TS01) nha.");
                    }
                    break;

                case "CHECKOUT":
                    // AI bóc tách được Tên, SĐT thì tiến hành thanh toán luôn
                    if (intent.getCustomerName() != null && intent.getCustomerPhone() != null) {
                        String adr = (intent.getAddress() != null) ? intent.getAddress() : "Tại cửa hàng";
                        handleCheckout(chatId, "/checkout " + intent.getCustomerName() + " " + intent.getCustomerPhone() + " " + adr);
                    } else {
                        send(chatId, "Bạn vui lòng cho mình xin Tên và SĐT để làm đơn thanh toán nhé!");
                    }
                    break;

                case "SHOW_MENU":
                    send(chatId, buildMenuText());
                    break;

                case "SHOW_CART":
                    send(chatId, buildCartText(chatId));
                    break;

                case "CLEAR":
                    carts.remove(chatId);
                    send(chatId, "Đã dọn sạch giỏ hàng rồi nhé.");
                    break;

                default:
                    // Nếu AI trả về câu trả lời tự nhiên (response)
                    if (intent.getResponse() != null && !intent.getResponse().isBlank()) {
                        send(chatId, intent.getResponse());
                    } else {
                        String ragAnswer = ragService.answerMenuQuestion(text);
                        send(chatId, ragAnswer != null ? ragAnswer : "Dạ, shop nghe nè! Bạn muốn xem /menu hay đặt món gì ạ?");
                    }
                    break;
            }

        } catch (Exception e) {
            log.error("Bot Error: ", e);
            send(chatId, "Máy chủ bận xíu, bạn thử lại sau nha.");
        }
    }
    private boolean isRelatedToShop(String text) {
        String t = text.toLowerCase();
        return t.contains("uống") || t.contains("món") || t.contains("trà") || t.contains("giá") || t.contains("ship");
    }

    private void handleSystemCommands(Long chatId, String text) {
        String lower = text.toLowerCase();
        if ("/start".equalsIgnoreCase(lower)) {
            send(chatId, "Hệ thống đặt hàng tự động 🤖\n- /menu: Xem thực đơn\n- /cart: Xem giỏ\n- /checkout <tên> <sđt> <địa chỉ>\n- /clear: Xóa giỏ");
        } else if (lower.startsWith("/add ")) handleAdd(chatId, text);
        else if (lower.startsWith("/checkout ")) handleCheckout(chatId, text);
        else if ("/menu".equalsIgnoreCase(lower)) send(chatId, buildMenuText());
        else if ("/cart".equalsIgnoreCase(lower)) send(chatId, buildCartText(chatId));
        else if ("/clear".equalsIgnoreCase(lower)) carts.remove(chatId);
    }

    private void handleAdd(Long chatId, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length != 4) { send(chatId, "Sai cú pháp. VD: /add TS01 M 2"); return; }

        String pid = parts[1].toUpperCase();
        String sz = parts[2].toUpperCase();
        int qty = Integer.parseInt(parts[3]);

        Product p = productService.getProductById(pid);
        if (p == null || !p.isAvailable()) { send(chatId, "Món này không có sẵn."); return; }

        List<CartItem> cart = carts.computeIfAbsent(chatId, k -> new ArrayList<>());

        // CỘNG DỒN NẾU TRÙNG MÓN + SIZE
        Optional<CartItem> existing = cart.stream().filter(i -> i.productId().equals(pid) && i.size().equals(sz)).findFirst();
        if (existing.isPresent()) {
            int idx = cart.indexOf(existing.get());
            CartItem old = existing.get();
            cart.set(idx, new CartItem(pid, p.getName(), sz, old.quantity() + qty, old.unitPrice()));
        } else {
            cart.add(new CartItem(pid, p.getName(), sz, qty, "M".equals(sz) ? p.getPriceM() : p.getPriceL()));
        }

        touchCart(chatId);
        send(chatId, "Đã thêm " + qty + " " + p.getName() + " (Size " + sz + ") vào giỏ.");
    }

    private void handleCheckout(Long chatId, String text) {
        List<CartItem> cart = carts.getOrDefault(chatId, List.of());
        if (cart.isEmpty()) { send(chatId, "Giỏ hàng trống."); return; }
        if (!acquireCheckoutLock(chatId)) return;

        try {
            String[] parts = text.split("\\s+", 4);
            if (parts.length < 3) { send(chatId, "Thiếu Tên hoặc SĐT."); return; }

            String name = parts[1].trim();
            String phone = parts[2].trim();
            String address = (parts.length == 4) ? parts[3].trim() : "Nhận tại cửa hàng";

            OrderResponse res = orderService.createOrder(CreateOrderRequest.builder()
                    .customerName(name).customerPhone(phone).address(address).telegramChatId(chatId)
                    .items(cart.stream().map(i -> CreateOrderItemRequest.builder()
                            .productId(i.productId()).size(i.size()).quantity(i.quantity()).build()).toList())
                    .build());

            PaymentResponse pay = payOsService.createPaymentForOrder(res.getId());
            carts.remove(chatId);
            send(chatId, "Đơn #" + res.getId() + " thành công! ✅\nTổng: " + res.getTotalAmount() + " VND\nLink: " + pay.getCheckoutUrl());
            if (pay.getQrCode() != null) sendQrImage(chatId, pay.getQrCode());
        } finally { releaseCheckoutLock(chatId); }
    }

    // Các hàm helper (send, sendQrImage, buildMenuText...) giữ nguyên như của bạn nhưng viết gọn lại
    private String buildMenuText() {
        List<Product> products = productService.getAllProducts().stream()
                .filter(Product::isAvailable)
                // Sắp xếp theo Category trước, sau đó mới đến ID món
                .sorted(Comparator.comparing(Product::getCategory).thenComparing(Product::getId))
                .toList();

        StringBuilder builder = new StringBuilder("🧋 MENU\n");
        String currentCategory = "";

        for (Product product : products) {
            // Nếu chuyển sang danh mục mới thì in tên danh mục đó ra
            if (!product.getCategory().equals(currentCategory)) {
                currentCategory = product.getCategory();
                builder.append("\n").append(currentCategory).append("\n");
            }
            // In chi tiết món theo định dạng đẹp
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
        if (cart.isEmpty()) return "Giỏ hàng trống.";
        StringBuilder sb = new StringBuilder("🛒 GIỎ HÀNG:\n");
        int total = 0;
        for (CartItem i : cart) {
            int sub = i.unitPrice() * i.quantity();
            total += sub;
            sb.append("- ").append(i.productName()).append(" (").append(i.size()).append(") x").append(i.quantity()).append(": ").append(sub).append(" VND\n");
        }
        sb.append("Tổng: ").append(total).append(" VND");
        return sb.toString();
    }

    private void send(Long chatId, String msg) {
        try { execute(SendMessage.builder().chatId(chatId.toString()).text(msg).build()); } catch (Exception e) {}
    }

    private void sendQrImage(Long chatId, String qr) {
        try {
            String url = "https://quickchart.io/qr?size=320&text=" + java.net.URLEncoder.encode(qr, "UTF-8");
            execute(org.telegram.telegrambots.meta.api.methods.send.SendPhoto.builder().chatId(chatId.toString())
                    .photo(new org.telegram.telegrambots.meta.api.objects.InputFile(url)).caption("QR Thanh toán").build());
        } catch (Exception e) {}
    }

    private boolean allowRequest(Long chatId) {
        long now = System.currentTimeMillis();
        Long last = lastRequestAt.put(chatId, now);
        return last == null || (now - last) >= REQUEST_COOLDOWN_MS;
    }
    private void touchCart(Long chatId) { cartTouchedAt.put(chatId, System.currentTimeMillis()); }
    private void clearExpiredCartIfNeeded(Long chatId) {
        Long t = cartTouchedAt.get(chatId);
        if (t != null && (System.currentTimeMillis() - t > CART_TIMEOUT_MS)) { carts.remove(chatId); cartTouchedAt.remove(chatId); }
    }
    private boolean acquireCheckoutLock(Long chatId) { return checkoutLocks.putIfAbsent(chatId, true) == null; }
    private void releaseCheckoutLock(Long chatId) { checkoutLocks.remove(chatId); }
    private record CartItem(String productId, String productName, String size, int quantity, int unitPrice) {}
}