# Tài liệu phân tích giải pháp triển khai TeaShop Telegram Bot

## 1. Mục tiêu của hệ thống

Dự án này là một backend Spring Boot phục vụ cho bot Telegram bán hàng của quán trà sữa Khánh. Hệ thống có 3 mục tiêu chính:

1. Nhận tin nhắn từ khách hàng qua Telegram và hỗ trợ xem menu, thêm món vào giỏ, đặt hàng, hủy đơn.
2. Lưu dữ liệu đơn hàng, thanh toán và sản phẩm trong PostgreSQL để đảm bảo trạng thái được bảo toàn.
3. Kết nối thêm các dịch vụ ngoài như Gemini, Qdrant và payOS để tăng trải nghiệm hội thoại, tìm kiếm thông tin và thanh toán tự động.

Giải pháp triển khai hiện tại dùng Render làm nền tảng host chính, còn cron-job.org đóng vai trò keep-alive để hạn chế tình trạng Render Free Tier bị ngủ sau thời gian không có truy cập.

## 2. Kiến trúc tổng quan

Hệ thống được thiết kế theo mô hình backend tập trung, trong đó Spring Boot là lõi xử lý nghiệp vụ. Các thành phần chính gồm:

- Telegram Bot: nhận và phản hồi tin nhắn từ khách hàng.
- REST API: cung cấp endpoint cho đơn hàng, thanh toán, danh mục sản phẩm và webhook.
- PostgreSQL: lưu sản phẩm, đơn hàng, thanh toán.
- Qdrant: lưu vector dữ liệu RAG để bot trả lời câu hỏi về menu và FAQ.
- Gemini API: hỗ trợ phân loại ý định, trả lời hội thoại tự nhiên và sinh câu trả lời dựa trên ngữ cảnh.
- payOS: xử lý link thanh toán, QR code và webhook xác nhận thanh toán.
- Render: host backend Spring Boot và PostgreSQL.
- cron-job.org: gọi định kỳ vào hệ thống để giữ app luôn ở trạng thái hoạt động.

Luồng tổng quát là: khách nhắn Telegram -> bot xử lý nghiệp vụ -> đọc/ghi PostgreSQL -> nếu cần sẽ gọi Gemini, Qdrant hoặc payOS -> phản hồi lại khách ngay trong Telegram.

## 3. Thành phần triển khai

### 3.1 Render

Render là nơi triển khai backend Spring Boot và kết nối với PostgreSQL. Trong cấu hình hiện tại, ứng dụng dùng biến `PORT` do Render cấp để bind cổng web, thể hiện ở [application.properties](../src/main/resources/application.properties). Điều này giúp ứng dụng chạy đúng trong môi trường PaaS thay vì hardcode cổng cố định.

Render có hai vai trò quan trọng:

- Tự động build lại ứng dụng mỗi khi Khánh push code mới lên GitHub.
- Host một endpoint HTTP để Telegram bot, PayOS webhook và cron-job.org có thể truy cập từ bên ngoài.

Với Free Tier, Render sẽ tự ngủ sau một khoảng thời gian không có traffic. Vì bot Telegram cần phản hồi gần như real-time, việc app ngủ sẽ gây cảm giác chậm ở lần truy cập đầu tiên. Đây là lý do cần thêm keep-alive.

### 3.2 PostgreSQL

Ứng dụng kết nối PostgreSQL qua JDBC URL cấu hình trong [application.properties](../src/main/resources/application.properties). `spring.jpa.hibernate.ddl-auto=update` cho phép Hibernate tự đồng bộ schema ở mức cơ bản trong quá trình chạy.

Database lưu các thực thể chính:

- `Product`: danh sách món, giá M/L, danh mục, trạng thái còn bán.
- `Order`: thông tin đơn hàng, khách hàng, trạng thái, tổng tiền.
- `OrderItem`: từng dòng món trong đơn.
- `Payment`: thông tin giao dịch payOS, trạng thái thanh toán, QR và link checkout.

### 3.3 cron-job.org

cron-job.org là dịch vụ gọi HTTP định kỳ vào hệ thống để giữ app tỉnh. Cách dùng ở đây là cấu hình một job ping vào URL gốc của Render, ví dụ `https://khanhs-tea-bot-nuzp.onrender.com`, theo chu kỳ 5-10 phút.

Mục đích không phải để xử lý nghiệp vụ, mà chỉ để tạo traffic đều đặn nhằm:

- Giảm khả năng Render Free Tier đi vào trạng thái ngủ.
- Giữ endpoint web luôn phản hồi nhanh hơn cho người dùng Telegram.
- Tránh cảm giác bot bị “đơ” ở lần nhắn đầu sau thời gian nhàn rỗi.

Trong code, endpoint gốc `/` được thiết kế riêng để phục vụ việc ping này. `HealthCheckController` trả về HTTP 200 với chuỗi `TeaShop Bot is Online!` để cron-job.org xác nhận hệ thống còn hoạt động.

## 4. Phân tích theo lớp chức năng

### 4.1 Lớp bot Telegram

Bot được đăng ký trong [TelegramBotInitializer](../src/main/java/com/khanh/teashop/khanhs_tea_bot/config/TelegramBotInitializer.java) bằng `TelegramBotsApi` và `DefaultBotSession`. Điều này có nghĩa bot chạy theo mô hình long polling, nhận cập nhật trực tiếp từ Telegram thay vì cần webhook Telegram riêng.

Lớp lõi là [TeaShopTelegramBot](../src/main/java/com/khanh/teashop/khanhs_tea_bot/telegram/TeaShopTelegramBot.java), nơi xử lý toàn bộ tin nhắn đến. Bot hỗ trợ các lệnh và luồng sau:

- `/start`: giới thiệu bot và liệt kê lệnh chính.
- `/menu`: xem menu hiện tại.
- `/add`: thêm món vào giỏ, hỗ trợ nhiều món trong một câu lệnh.
- `/remove`: xóa món khỏi giỏ.
- `/cart`: xem giỏ hàng.
- `/checkout`: tạo đơn hàng và sinh link thanh toán.
- `/cancel`: hủy đơn.

Ngoài lệnh tường minh, bot còn có 3 lớp hiểu ngôn ngữ:

- Tự nhận dạng câu thêm món bằng regex tự nhiên tiếng Việt.
- Gọi Gemini để phân loại intent như xem menu, xem giỏ, checkout.
- Dùng RAG để trả lời các câu hỏi liên quan menu hoặc FAQ.

### 4.2 Lớp đơn hàng

[OrderService](../src/main/java/com/khanh/teashop/khanhs_tea_bot/service/OrderService.java) là nơi tạo, đọc và hủy đơn. Khi tạo đơn, service:

- Kiểm tra sản phẩm tồn tại.
- Kiểm tra món còn bán.
- Chuẩn hóa size chỉ nhận `M` hoặc `L`.
- Tính tổng tiền theo từng dòng món.
- Lưu đơn với trạng thái ban đầu `PENDING`.

Việc hủy đơn cũng được chặn logic quan trọng: đơn đã `PAID` thì không cho hủy nữa. Cách này tránh tình trạng khách đã thanh toán nhưng vẫn thao tác hủy ở tầng nghiệp vụ.

### 4.3 Lớp thanh toán payOS

[PayOsService](../src/main/java/com/khanh/teashop/khanhs_tea_bot/service/PayOsService.java) chịu trách nhiệm tạo giao dịch thanh toán, nhận webhook và cập nhật trạng thái.

Khi tạo payment:

- Hệ thống lấy đơn hàng từ PostgreSQL.
- Kiểm tra đơn chưa được thanh toán.
- Tạo `orderCode` riêng cho payOS.
- Gửi request tới API payOS kèm chữ ký HMAC SHA256.
- Nhận về `checkoutUrl` và `qrCode`.
- Lưu thông tin thanh toán vào database với trạng thái `PENDING`.

Khi payOS gọi webhook thành công:

- Hệ thống đọc `orderCode` từ payload.
- Tìm payment tương ứng trong database.
- Nếu mã phản hồi là `00`, cập nhật payment thành `PAID` và order thành `PAID`.
- Gửi thông báo lại cho khách qua Telegram bằng `TelegramNotifyService`.

Điểm đáng chú ý là bot có thể tạo cả link thanh toán lẫn QR code để khách thanh toán nhanh, giúp quy trình đặt hàng liền mạch hơn.

### 4.4 Lớp hỏi đáp RAG

[RagDataInitializer](../src/main/java/com/khanh/teashop/khanhs_tea_bot/config/RagDataInitializer.java) chạy khi ứng dụng khởi động để nạp dữ liệu menu và FAQ vào Qdrant. Dữ liệu gồm:

- Từng sản phẩm trong menu.
- FAQ cơ bản như giờ mở cửa và phí ship.

[RagService](../src/main/java/com/khanh/teashop/khanhs_tea_bot/service/RagService.java) sẽ:

- Phân loại câu hỏi là FAQ, menu hay câu hỏi chung.
- Truy vấn Qdrant theo nguồn dữ liệu phù hợp.
- Gửi ngữ cảnh sang Gemini để tạo câu trả lời tự nhiên.
- Nếu Gemini rate-limit hoặc lỗi, tự chuyển sang câu trả lời fallback dựa trên ngữ cảnh đã tìm được.

Điểm mạnh của cách này là bot không chỉ trả lời theo kịch bản cứng mà có thể giải thích menu bằng ngôn ngữ tự nhiên, thân thiện hơn với khách hàng.

### 4.5 Lớp danh mục sản phẩm

[ProductService](../src/main/java/com/khanh/teashop/khanhs_tea_bot/service/ProductService.java) chỉ có hai nhiệm vụ chính:

- Lấy toàn bộ sản phẩm.
- Lấy chi tiết một sản phẩm theo mã.

Service này được dùng ở nhiều nơi: bot Telegram, RAG initializer, order service và các REST controller. Điều đó giúp nguồn dữ liệu sản phẩm được dùng thống nhất, tránh lệch logic giữa nhiều luồng xử lý.

## 5. Các endpoint quan trọng

### 5.1 Endpoint gốc cho keep-alive

`GET /`

Controller trả về chuỗi text đơn giản để cron-job.org ping định kỳ. Đây là endpoint quan trọng nhất cho mục tiêu giữ Render luôn chạy.

### 5.2 Endpoint sản phẩm

`GET /products`

`GET /products/{id}`

Hai endpoint này cho phép đọc danh sách sản phẩm và xem chi tiết món.

### 5.3 Endpoint đơn hàng

`POST /orders`

`GET /orders/{id}`

`GET /orders`

`PATCH /orders/{id}/cancel`

Đây là nhóm endpoint CRUD chính cho order, dùng khi cần thao tác từ ngoài Telegram hoặc phục vụ kiểm thử, quản trị.

### 5.4 Endpoint thanh toán

`POST /payments/orders/{orderId}/create`

`GET /payments/orders/{orderId}`

`POST /payments/webhook/payos`

`GET /payments/ping`

Trong đó `/payments/ping` là endpoint phụ trợ để kiểm tra service thanh toán và cũng có thể được dùng như một đường ping bổ sung.

## 6. Luồng nghiệp vụ end-to-end

### 6.1 Khách xem menu và thêm món

Khách nhắn `/menu` hoặc hỏi tự nhiên về món. Bot lấy danh sách sản phẩm từ PostgreSQL, lọc món còn bán và trả về menu theo danh mục.

Khi khách thêm món bằng `/add` hoặc câu tự nhiên, bot cập nhật giỏ hàng trong bộ nhớ runtime theo từng chatId. Giỏ hàng có timeout 30 phút, nếu quá thời gian không thao tác sẽ tự xóa.

### 6.2 Khách checkout

Khi khách nhắn `/checkout <tên> <sđt> [địa_chỉ]`, bot sẽ:

- Kiểm tra giỏ hàng có dữ liệu hay chưa.
- Kiểm tra số điện thoại hợp lệ.
- Tạo `CreateOrderRequest` và gọi `OrderService`.
- Sau đó gọi `PayOsService` để sinh thanh toán.
- Trả về mã đơn, tổng tiền, trạng thái và link thanh toán.
- Nếu có QR code, bot gửi ảnh QR để khách quét luôn.

### 6.3 Thanh toán thành công

Sau khi khách thanh toán qua payOS, webhook sẽ gọi về `/payments/webhook/payos`. Khi hệ thống nhận code thành công:

- Payment chuyển `PENDING` sang `PAID`.
- Order chuyển sang `PAID`.
- Bot nhắn lại cho khách rằng thanh toán thành công.

### 6.4 Keep-alive

cron-job.org ping vào endpoint gốc của Render theo lịch. Chỉ cần HTTP 200 phản hồi là đủ để duy trì “nhịp sống” của app. Về mặt nghiệp vụ, request này không làm thay đổi dữ liệu nào trong hệ thống.

## 7. Cấu hình runtime quan trọng

Trong [application.properties](../src/main/resources/application.properties), các biến sau là cốt lõi cho triển khai thực tế:

- `server.port=${PORT:8080}`: tương thích Render.
- `spring.datasource.*`: kết nối PostgreSQL.
- `telegram.bot.token=${TELEGRAM_BOT_TOKEN}`: token bot Telegram.
- `gemini.api-key=${GEMINI_API_KEY}`: gọi Gemini.
- `qdrant.url`, `qdrant.api-key`: kết nối Qdrant.
- `payos.*`: cấu hình payment gateway.
- `payos.return-url` và `payos.cancel-url`: quay về URL public của Render sau thanh toán.

Việc dùng biến môi trường thay vì hardcode giúp an toàn hơn khi deploy và dễ thay đổi giữa môi trường local, staging và production.

## 8. Vì sao giải pháp này phù hợp

Giải pháp hiện tại phù hợp với quy mô bot bán hàng nhỏ đến vừa vì:

- Chi phí thấp, có thể tận dụng Render Free Tier.
- Triển khai đơn giản bằng GitHub push là tự build.
- Phù hợp với mô hình bot Telegram cần phản hồi nhanh.
- Có thể mở rộng dần bằng các service ngoài như RAG, Gemini và payOS mà không phải tách hệ thống quá sớm.

cron-job.org là một lựa chọn thực dụng để xử lý nhược điểm của Free Tier. Nó không thay thế scaling thật sự, nhưng giúp cải thiện trải nghiệm đủ tốt ở giai đoạn đầu.

## 9. Hạn chế và rủi ro

Giải pháp hiện tại vẫn có một số điểm cần lưu ý:

- Render Free Tier có giới hạn tài nguyên và vẫn có thể chậm khi cold start.
- Keep-alive chỉ giảm xác suất ngủ app, không đảm bảo tuyệt đối 100% uptime.
- Giỏ hàng đang lưu trong bộ nhớ runtime của ứng dụng, nên sẽ mất nếu app restart.
- RAG và Gemini phụ thuộc dịch vụ ngoài, nếu quota hoặc network có vấn đề thì bot sẽ fallback nhưng chất lượng trả lời có thể giảm.
- Webhook payOS cần public URL ổn định; thay đổi domain phải cập nhật lại cấu hình.

## 10. Kết luận

Kiến trúc hiện tại là một giải pháp hợp lý cho mô hình bot Telegram bán hàng của quán trà sữa Khánh. Render đảm nhiệm phần host backend và database, còn cron-job.org giữ cho app không ngủ quá lâu trong môi trường Free Tier. Kết hợp với Telegram bot, payOS, Gemini và Qdrant, hệ thống có đủ khả năng phục vụ đặt món, thanh toán, hỏi đáp và thông báo trạng thái tự động theo hướng 24/7.

Nếu muốn nâng cấp về sau, bước tự nhiên tiếp theo sẽ là tách giỏ hàng sang Redis hoặc database, bổ sung giám sát uptime và cân nhắc nâng cấp lên gói hosting ít bị sleep hơn.