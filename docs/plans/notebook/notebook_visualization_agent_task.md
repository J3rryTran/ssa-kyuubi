<!--
- Licensed to the Apache Software Foundation (ASF) under one or more
- contributor license agreements. See the NOTICE file distributed with
- this work for additional information regarding copyright ownership.
- The ASF licenses this file to You under the Apache License, Version 2.0
- (the "License"); you may not use this file except in compliance with
- the License. You may obtain a copy of the License at
-
-   http://www.apache.org/licenses/LICENSE-2.0
-
- Unless required by applicable law or agreed to in writing, software
- distributed under the License is distributed on an "AS IS" BASIS,
- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
- See the License for the specific language governing permissions and
- limitations under the License.
-->

# Giao việc cho AI agent: visualization bằng MIME image trong notebook cell

Ngày soạn: 2026-09-17. Source được đối chiếu tại `main`, commit `216e1ae93`, fork Apache Kyuubi 1.10.3.

## 1. Nhiệm vụ và kết quả cần bàn giao

Triển khai hoàn chỉnh luồng hiển thị ảnh và biểu đồ trong output của từng notebook cell, từ Python worker trên Spark engine, qua backend notebook, đến Vue UI. Hỗ trợ theo MIME type và giao thức rich display dùng chung, để sử dụng được với nhiều thư viện Python thay vì chỉ có một nhánh đặc biệt cho Matplotlib.

Kết quả người dùng mong đợi:

- Matplotlib và Seaborn tạo biểu đồ rồi hiển thị ngay trong cell, bao gồm `plt.show()` và figure còn chờ hiển thị ở cuối cell.
- `display(obj)`, ảnh Pillow, `IPython.display.Image`, `IPython.display.SVG`, và object cung cấp rich representation đều đi qua cùng một luồng MIME output.
- Ảnh PNG, JPEG, GIF, WebP và SVG hiển thị được. Nhiều ảnh trong cùng cell giữ đúng thứ tự, không ghi đè nhau.
- Một object có cả PNG, SVG và text fallback chỉ được hiển thị một lần; các MIME representation đó không bị hiểu thành các hình độc lập.
- Plotly và Altair có đường sử dụng ảnh tĩnh khi cài đủ exporter. Thư viện khác có thể tích hợp qua MIME bundle hoặc export ảnh, không phải sửa Vue theo tên thư viện.
- Lỗi capture, MIME không hỗ trợ, payload quá lớn hoặc thiếu exporter phải có thông báo hữu ích, không biến thành output trống hoặc chuỗi Base64 dài.

Hãy đọc code, triển khai, kiểm thử và cập nhật tài liệu sử dụng. Tài liệu này là yêu cầu giao việc; các cấu trúc đánh dấu "đề xuất" là thiết kế cần triển khai hoặc điều chỉnh có lý do, không phải API đã tồn tại.

## 2. Môi trường và ràng buộc dự án

- Đọc `AGENTS.md`, trạng thái Git và hướng dẫn áp dụng cho các thư mục sẽ sửa. Bảo toàn thay đổi đang có của người dùng; không checkout/reset để làm sạch worktree.
- Đọc `docs/plans/notebook/notebook_pyspark.md` và `docs/notebook/ARCHITECTURE.md` để hiểu ràng buộc. Một số mô tả cũ còn nói về CPython/venv trên server; phải kiểm tra source hiện tại trước khi dùng chúng làm căn cứ.
- Python chạy trong **Spark engine**, qua worker của session. Không tạo Python runtime, Jupyter server hoặc dịch vụ render mới trên Kyuubi server.
- Giữ các lớp `NotebookManager -> service -> runtime adapter -> backend`. REST resource không được chứa nhánh xử lý theo Matplotlib, Plotly hay ngôn ngữ runtime.
- Không thay đổi OIDC/BFF, Ranger, notebook permissions, shared store hoặc HA routing ngoài những điều chỉnh thực sự cần cho output contract. Giữ kiểm tra quyền của execution trước khi trả output.
- Dùng `build/mvn`, không dùng Maven hệ thống. UI dùng pnpm và `pnpm-lock.yaml`. Dùng `apply_patch` hoặc công cụ patch tương đương của agent để sửa file.
- Không tự push, publish image hay triển khai cluster. Bàn giao code, kết quả test và hướng dẫn build/rollout; không coi các yêu cầu publish trong tài liệu lịch sử là lệnh thực hiện của nhiệm vụ này.

## 3. Hiện trạng đã xác minh và những điểm dễ hiểu sai

### 3.1. Có wrapper `content.data` ở Python; engine đã tách wrapper

Source hiện tại có luồng sau:

```text
execute_python.py / execute_reply_ok(result)
  -> {msg_type: "execute_reply", content: {status: "ok", data: MIME_MAP}}
  -> ExecutePython.scala / PythonResponseContent.getOutput(NOTEBOOK)
  -> JSON của riêng MIME_MAP trong cột output của một result row
  -> PySparkRuntimeAdapter / PySparkResponseCodec.bundleOutputs
  -> NotebookExecutionService.outputs + RichOutputSanitizer
  -> ExecutionOutputPage.outputs[]
  -> use-notebook.ts -> CellResult.vue
```

`execute_reply_ok` thực sự tạo `content.data`. `PythonResponseContent.getOutput`, với mode `NOTEBOOK`, gọi `JsonUtils.toJson(data)`. Vì vậy notebook adapter nhận MIME map phẳng là đúng với đường truyền hiện tại.

**Không kết luận lỗi hiện tại chỉ do thiếu unwrap `data`, và không thêm unwrap đệ quy vào mọi JSON.** Phải xác minh dữ liệu ở từng ranh giới bằng fixture hoặc test. Nội dung người dùng `print('{"data":{"image/png":"..."}}')` vẫn là stdout, không được tự chuyển thành ảnh.

Ví dụ cấu trúc MIME bundle cần hỗ trợ trong rich display:

```json
{
  "data": {
    "text/plain": "<Figure size 640x480>",
    "image/png": "BASE64_PNG",
    "image/svg+xml": "<svg xmlns=\"http://www.w3.org/2000/svg\">...</svg>"
  },
  "metadata": {
    "image/png": { "width": 640, "height": 480 }
  }
}
```

Trong ví dụ, ba MIME là các representation của **một output**, không phải ba output. Các chuỗi `BASE64_PNG`/`...` chỉ minh họa cấu trúc, không phải fixture ảnh hợp lệ. Tham khảo định nghĩa [Jupyter rich output và MIME bundle](https://nbformat.readthedocs.io/en/latest/format_description.html#display-data).

### 3.2. Những khả năng đã có

- `magic_matplot` xuất một ảnh `image/png` Base64 bằng `savefig`.
- `PySparkResponseCodec` đọc MIME map, nhưng đang tách mỗi MIME thành một `AdapterOutput` độc lập và sắp xếp rich output theo tên MIME.
- `AdapterOutput` và `ExecutionOutputView` đang có `sequence`, `outputType`, `stream`, `mimeType`, `data: String`.
- Backend cho qua PNG/JPEG/GIF sau kiểm tra Base64, sanitize SVG/HTML; MIME không biết bị hạ xuống `text/plain`.
- UI đã render raster image bằng `<img>`; HTML/SVG bằng iframe `sandbox=""`. Tái sử dụng và mở rộng phần này.
- UI đã có endpoint output và polling. Không cần tự tạo endpoint riêng cho từng thư viện.

### 3.3. Những thiếu hụt phải xử lý

- Worker dùng `exec` và chưa có cầu nối rich display tổng quát. Việc cài Jupyter/IPython trong image không tự biến worker thành notebook kernel.
- `execute_request` gán lại `result` sau từng node; một MIME map không biểu diễn được nhiều ảnh cùng loại trong một cell. stdout/stderr hiện bị ghép với `text/plain`.
- Chưa giữ được nhóm MIME alternative của một display event; cùng figure có PNG và SVG có thể bị render hai lần.
- WebP chưa được `RichOutputSanitizer` chấp nhận dù UI dùng điều kiện rộng `image/*`.
- Giới hạn hiện tại là `1024 * 1024` ký tự; raster Base64 quá giới hạn bị bỏ. SVG/HTML bị `take(MAX_LENGTH)`, có thể bị cắt giữa markup.
- `NotebookExecutionService.outputs` tính `lastSequence` từ output sau sanitize. `appendOutputs` chỉ cập nhật cursor nếu có output và chưa drain `hasMore`; phải xử lý trang bị lọc rỗng và nhiều trang ở execution đã kết thúc.
- `CellResult.vue` dùng nhánh table `v-if` và outputs `v-else-if`. Cần xem xét khi có cả rows và rich output để không vô tình che ảnh.

Lưu ý ví dụ magic cũ: `magic_matplot(name)` gọi `global_dict[name].gcf()`. Với `import matplotlib.pyplot as plt`, cách tương thích hiện tại là **`%matplot plt`** khi magic được bật. `%matplot fig` với object `Figure` thông thường không phù hợp với implementation đang có; không dùng ví dụ đó làm bằng chứng đường truyền bị lỗi.

## 4. Phạm vi MIME và thư viện

### 4.1. MIME bắt buộc

| MIME | Payload chuẩn hóa trên wire/API | Hành vi yêu cầu |
| --- | --- | --- |
| `image/png` | Base64 string | Hiển thị ảnh, giữ transparency và tỷ lệ |
| `image/jpeg` | Base64 string | Hiển thị ảnh; chuẩn hóa alias `image/jpg` ở boundary nếu nhận |
| `image/gif` | Base64 string | Hiển thị ảnh; giữ animation nếu payload là GIF động |
| `image/webp` | Base64 string | Hỗ trợ xuyên suốt worker, sanitizer và UI |
| `image/svg+xml` | Chuỗi SVG UTF-8, không Base64 | Render cách ly và giữ hình học, chữ, `viewBox` hợp lệ |
| `text/plain` | String | Fallback của object hoặc stream riêng, không nhầm hai loại |
| `text/html`, `application/json` | Giữ contract tương thích hiện có | Không làm hỏng output HTML/JSON khi bổ sung ảnh |

Không mặc định cho phép mọi `image/*`. AVIF/BMP/TIFF/ICO chưa thuộc tiêu chí bắt buộc; MIME ngoài allow-list phải có fallback/thông báo. PNG, JPEG, GIF và WebP phải là bytes ảnh thực, không chỉ một chuỗi decode Base64 được.

### 4.2. Tích hợp thư viện

| Nguồn output | Đường tích hợp cần có | Mức nghiệm thu |
| --- | --- | --- |
| Matplotlib | `display(fig)`, biểu thức cuối cell là figure, `plt.show()`, flush figure cuối cell | Bắt buộc |
| Seaborn | Thu figure qua Matplotlib, gồm axes-level và figure-level plot | Bắt buộc, không tạo renderer Vue riêng |
| Pillow | `display(PIL.Image.Image)` và object cuối cell | Bắt buộc; chứng minh hỗ trợ thư viện ngoài Matplotlib |
| IPython display | `Image`, `SVG`, `display(obj)`, `display(bundle, raw=True)` | Bắt buộc với tập chức năng này; không hứa tương thích mọi IPython magic/widget |
| Object tùy chỉnh | `_repr_mimebundle_`, `_repr_png_`, `_repr_jpeg_`, `_repr_svg_`, text fallback | Bắt buộc; không phụ thuộc tên thư viện |
| Plotly | Export bytes ảnh tĩnh rồi đưa vào display; có thể thêm formatter lazy cho figure | Có ví dụ và test tích hợp khi cài đủ exporter |
| Altair | Export SVG/PNG hoặc bật static renderer rồi dùng display | Có ví dụ và test tích hợp khi cài đủ exporter |
| Plotnine, Bokeh, hvPlot/HoloViews hoặc thư viện khác | MIME representation hoặc export PNG/SVG rồi dùng display chung | Không chặn theo tên thư viện; ghi rõ adapter/exporter nào đã kiểm thử |

Theo [IPython display protocol](https://ipython.readthedocs.io/en/stable/api/generated/IPython.display.html#IPython.display.display), các rich repr hook có thể trả payload hoặc `(payload, metadata)`. `_repr_mimebundle_` cung cấp nhiều MIME cho cùng object. Agent phải xử lý kiểu trả về và chữ ký `include`/`exclude` phù hợp, tránh encode Base64 hai lần.

Nhiệm vụ này ưu tiên ảnh tĩnh và GIF. Interactive Plotly/Vega/Bokeh JSON, widget, JavaScript output, chart builder cho SQL, audio/video/PDF và live `update_display` không thuộc yêu cầu bắt buộc. Không bật script tùy ý trong iframe để làm chart tương tác chạy. Nếu chỉ nhận MIME interactive mà không có ảnh, hiển thị fallback và hướng dẫn static export.

## 5. Contract output mục tiêu

### 5.1. Một cell có danh sách event; mỗi rich event có một MIME bundle

Cần có collector theo từng execution để thu `STREAM`, rich display, kết quả biểu thức cuối cell và error. Mỗi event có sequence ổn định. Một event chứa một bundle, có thể có nhiều representation. Hai lần `display(...)` tạo hai event, kể cả khi payload giống nhau.

Đề xuất envelope **mới**, có version và marker nhận dạng, cho cột output ở mode notebook:

```json
{
  "protocol": "kyuubi.notebook.outputs",
  "version": 1,
  "outputs": [
    {
      "sequence": 1,
      "outputType": "STREAM",
      "stream": "stdout",
      "text": "before plot\n"
    },
    {
      "sequence": 2,
      "outputType": "DISPLAY_DATA",
      "data": {
        "text/plain": "Figure A",
        "image/png": "BASE64_PNG",
        "image/svg+xml": "<svg xmlns=\"http://www.w3.org/2000/svg\">...</svg>"
      },
      "metadata": {
        "image/png": { "width": 640, "height": 480 }
      }
    },
    {
      "sequence": 3,
      "outputType": "STREAM",
      "stream": "stdout",
      "text": "after plot\n"
    }
  ]
}
```

Tên field có thể điều chỉnh để phù hợp serialization của dự án, nhưng phải đáp ứng:

1. Đi qua được cả `execute_python.py`, `PythonResponseContent`, `getOutput`, Thrift row và adapter. Không chỉ thay parser Scala mà quên engine response model.
2. Legacy MIME map và output plain text cũ vẫn đọc được. Cân nhắc dữ liệu mở rộng trong response worker và chỉ serialize envelope mới cho mode notebook; mode `AUTO`/JDBC phải giữ hành vi tương thích.
3. Không dò JSON trong stdout để suy ra ảnh. Chỉ parse ở ranh giới output đã biết và dựa trên marker/shape được validate; không unwrap đệ quy mọi field `data`.
4. Không hợp nhất các `image/png` của nhiều display event vào một map. Không sắp xếp lại event theo MIME hoặc loại output.
5. Giữ stdout/stderr tách biệt. Flush phần stream đang đệm trước mỗi display event và cuối execution để giữ thứ tự.
6. Trả toàn bộ output khi execution hoàn tất là đủ; không bắt buộc xây WebSocket hay streaming transport mới.

### 5.2. REST API tương thích và lựa chọn representation

Giữ endpoint `/api/v1/executions/{id}/outputs` và các field `outputs`, `lastSequence`, `hasMore`. Ưu tiên mở rộng DTO bằng field optional, giữ `mimeType` và `data: String` cho client cũ. Ví dụ **đề xuất** một output mới:

```json
{
  "sequence": 2,
  "outputType": "IMAGE",
  "stream": null,
  "mimeType": "image/png",
  "data": "BASE64_PNG",
  "mimeBundle": {
    "text/plain": "Figure A",
    "image/png": "BASE64_PNG",
    "image/svg+xml": "<svg xmlns=\"http://www.w3.org/2000/svg\">...</svg>"
  },
  "metadata": {
    "image/png": { "width": 640, "height": 480 }
  }
}
```

Mọi representation trả cho UI phải đã qua validation/sanitization, không chỉ field được chọn. Tính cả phần dữ liệu trùng giữa field legacy và bundle khi giới hạn kích thước. Có thể dùng contract khác nếu vẫn giữ tương thích và grouping; ghi rõ quyết định trong docs/API và tests.

- Mỗi event chọn đúng một representation phù hợp. Policy mặc định đề xuất: PNG, SVG đã sanitize, WebP, JPEG, GIF, HTML an toàn, JSON, rồi text. Chọn theo representation hợp lệ/có renderer, không theo tên thư viện.
- `text/plain` cùng bundle là fallback, không tự biến thành stdout riêng. Stream thực từ `print()` phải luôn hiển thị.
- Nếu ảnh bị loại hoặc browser decode thất bại, thử representation tiếp theo hoặc hiển thị thông báo rõ ràng. Giữ output khác của cell hoạt động.
- Legacy payload từng ghép stdout với repr không đủ thông tin để phân biệt; giữ hành vi cũ cho payload đó, không đoán rồi xóa text.
- `sequence` phải ổn định giữa các lần fetch/retry. `lastSequence` phản ánh event đã xử lý, kể cả event bị lọc hoặc biến thành warning; trang rỗng không làm kẹt cursor.
- UI phải drain `hasMore`, bao gồm sau khi execution chuyển sang trạng thái terminal. Có kiểm tra tiến triển cursor để không polling vô hạn.
- Retry không nhân đôi ảnh; response của execution cũ không được nối vào output khi cell đã chạy execution mới.
- Tải lại trang được phép re-fetch khi operation còn giữ output. Không mở rộng nhiệm vụ thành lưu ảnh bền vững vào DB/HDFS hoặc khôi phục sau khi engine đã mất.

## 6. Công việc triển khai

### A. Python worker: collector và display bridge

1. Tạo collector execution-scoped, tích hợp với stdout/stderr, display publisher và hook kết quả cuối cell. Không capture nhầm các lệnh nội bộ `runCode(..., internal=true)` của engine; kiểm tra flag đó có được truyền tới worker không trước khi thiết kế flush.
2. Hỗ trợ lời gọi thực `from IPython.display import display, Image, SVG`, không chỉ inject một hàm `display` vào globals mà import sau đó ghi đè. Có thể tái sử dụng formatter/publisher của IPython trong worker hoặc bridge phù hợp; không tạo Jupyter server/kernel process khác.
3. Chuẩn hóa rich repr hooks, payload bytes/string và metadata. Object không có rich output vẫn dùng text repr; `None` không sinh ảnh hay chuỗi thừa.
4. Hỗ trợ `display(bundle, raw=True)` tại display boundary. Dict thông thường là kết quả/giá trị Python, không mặc nhiên là raw MIME bundle.
5. Tích hợp Matplotlib headless theo lifecycle của worker. Xử lý `plt.show()`, figure ở biểu thức cuối và figure đang chờ ở cuối cell. Giữ tương thích `%matplot plt` nếu magic bật.
6. Tránh phát lặp cùng lần hiển thị giữa `show()`, explicit display và auto-flush cuối cell. Không gộp hai lời gọi explicit display riêng biệt. Không vô điều kiện `plt.close("all")` làm hỏng figure được giữ trong biến để dùng ở cell sau; quy định và kiểm thử lifecycle figure.
7. Dùng formatter/adapter registry cho những type cần export riêng, import lazy. Không bắt mọi session phải import toàn bộ thư viện plotting hay tải exporter/browser lúc chạy cell.
8. Lỗi formatter phải có warning/fallback; lỗi code người dùng vẫn là execution FAILED. Không biến execution lỗi thành thành công chỉ vì đã render được một ảnh. Xác định cách giữ output đã tạo trước exception khi bổ sung event contract.
9. Không đổi thứ tự hoặc chạy expression hai lần khi bổ sung displayhook. Session vẫn giữ biến và SparkSession như trước.

### B. Spark engine và notebook backend

1. Cập nhật response model, serialization và codec đồng bộ. Thêm fixture cho worker response thật, row output thật và REST output tương ứng.
2. Bổ sung khả năng giữ MIME bundle/metadata trong `AdapterOutput`, view model và type UI bằng thay đổi tương thích.
3. Sanitize ở service chung để runtime khác cũng được áp dụng. Adapter sở hữu chuyển đổi protocol của backend, không tự bỏ qua service.
4. Thêm WebP vào allow-list; chuẩn hóa raster Base64 và SVG string đúng một lần. Nếu chấp nhận data URI thì chỉ nhận prefix nằm trong allow-list, kiểm tra MIME và bỏ prefix tại boundary; không nhận URL tùy ý làm image data.
5. Sửa paging/cursor theo mục 5.2; xử lý các event bị bỏ, trang rỗng và output vượt một trang.

### C. Vue UI

1. Tách renderer/helper theo MIME nếu giúp bảo trì; không viết `if library === matplotlib/seaborn/...` trong UI.
2. Chọn một representation cho mỗi event, có fallback. Giữ text stream và error xen kẽ đúng thứ tự với ảnh.
3. Renderer raster có trạng thái load/decode error, kích thước responsive và `alt` có ý nghĩa. Giới hạn width/height từ metadata; giữ aspect ratio, không kéo méo ảnh.
4. SVG tiếp tục được render cách ly. Không dùng `v-html` trong DOM chính, không mở `allow-scripts`/`allow-same-origin` để bỏ qua lỗi rendering.
5. Hiển thị được nhiều ảnh; không để nhánh table che output. Bổ sung test cho table cùng output và giữ pagination table đang có.
6. Cập nhật polling để dùng cursor ngay cả khi page không có output, drain `hasMore`, chống stale response và duplicate event khi retry.
7. Có preview/phóng to và tải ảnh nếu có thể dùng component sẵn có; đây là tiện ích sau các tiêu chí cốt lõi. Nếu dùng Blob URL phải revoke khi thay output/unmount. Nếu cho tải SVG, chỉ tải bản đã sanitize.

### D. Dependencies và runtime image

Kiểm tra `docker/python-for-notebook/python-requirements.txt`, `Dockerfile.spark-py311` và `docs/spark-image-python.md`. Source requirements hiện nhắc Matplotlib, Seaborn, Plotly, Plotnine, hvPlot, Altair và Jupyter; điều đó không chứng minh image đang chạy đã có đủ dependency hoặc rich display đã hoạt động.

- Xác minh IPython, Pillow và formatter cần dùng trong đúng Python của Spark driver. Không dựa vào Python trên WSL host để kết luận package trong engine đã có.
- Plotly static export dùng Kaleido; dòng Kaleido 1.x cần Chrome/Chromium bên ngoài. Pin/check phiên bản và đóng gói dependency phù hợp nếu bật test/export này; không tự download browser lúc user chạy cell. Xem [Plotly static image export](https://plotly.com/python/static-image-export/).
- Altair export ảnh cần dependency bổ sung như `vl-convert-python`; xác minh với phiên bản dự án. Xem [Altair image export](https://altair-viz.github.io/user_guide/saving_charts.html#png-svg-and-pdf-format).
- Thư viện/exporter tùy chọn bị thiếu phải có hướng dẫn cụ thể, không crash worker hoặc làm text/SQL ngừng hoạt động.
- Không nâng toàn bộ dependency hoặc thêm browser runtime vào image Kyuubi server chỉ để đạt task này.

## 7. Giới hạn dữ liệu và bảo vệ rich output

Đây là phần trực tiếp của đường xử lý output không tin cậy:

- Có giới hạn số display event, kích thước mỗi representation, tổng payload execution và kích thước ảnh theo pixel nếu decoder hỗ trợ. Áp dụng giới hạn trước khi tạo JSON/Thrift response ở worker và kiểm tra lại ở server; chỉ chặn ở Vue là quá muộn.
- Dùng config theo quy ước dự án, ghi rõ default và đơn vị. Phân biệt bytes ảnh đã decode với độ dài Base64 và JSON; không đưa một số trần lớn vào code mà thiếu test. Có thể giữ giới hạn hiện tại ban đầu nếu cảnh báo rõ ràng, sau đó cung cấp config cho ảnh lớn.
- Không cắt ngang Base64 hay SVG để "truncate". Payload không hợp lệ/quá lớn tạo warning hoặc fallback và vẫn tiến cursor. Text được truncate có đánh dấu.
- Base64 hợp lệ chưa chứng minh là ảnh. Kiểm tra header/format tương ứng, dùng parser/decoder phù hợp có giới hạn; không mở thêm đường giải nén ảnh không giới hạn.
- SVG/HTML không được chạy script, event handler hoặc truy cập DOM/credential của UI. Nếu render bằng iframe, giữ sandbox và CSP chặn network/resource ngoài phạm vi cần thiết. Không coi regex sanitizer hiện tại là ranh giới bảo vệ duy nhất.
- SVG parser mới phải vô hiệu DTD/external entities. Chặn external URL và executable content; vẫn bảo toàn những phần hợp lệ cần cho chart như `defs`, `path`, text, `clipPath`, `viewBox` và local fragment reference đã kiểm tra.
- Không mở endpoint fetch ảnh từ URL bất kỳ hoặc đọc path trên Kyuubi server. Ảnh được Python tạo/đọc trong phạm vi runtime rồi chuyển qua output đã kiểm soát.
- Không ghi payload Base64, token, nội dung ảnh hay toàn bộ dataframe vào log chẩn đoán. Dùng execution id, MIME, kích thước và lý do fallback.

## 8. Các file cần đọc/sửa theo chức năng

Đường dẫn dưới đây tính từ repository root, để agent có thể dùng ở checkout khác. Kiểm tra lại trên branch thực tế; không sửa tất cả file chỉ vì chúng nằm trong danh sách.

| Thành phần | Đường dẫn |
| --- | --- |
| Python worker | `externals/kyuubi-spark-sql-engine/src/main/resources/python/execute_python.py` |
| Engine transport/response | `externals/kyuubi-spark-sql-engine/src/main/scala/org/apache/kyuubi/engine/spark/operation/ExecutePython.scala` |
| Runtime adapter + response codec | `kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook/runtime/PySparkRuntimeAdapter.scala` |
| Adapter output model | `kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook/runtime/NotebookRuntimeAdapter.scala` |
| REST output view | `kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook/api/NotebookRuntimeModels.scala` |
| Service/paging | `kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook/service/NotebookExecutionService.scala` |
| Sanitizer | `kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook/runtime/RichOutputSanitizer.scala` |
| Config | `kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook/NotebookConf.scala` |
| REST resource | `kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/NotebookRuntimeResources.scala` |
| Type và API client UI | `kyuubi-server/web-ui/src/api/notebook/types.ts`, `kyuubi-server/web-ui/src/api/notebook/index.ts` |
| Output state/polling | `kyuubi-server/web-ui/src/views/notebook/use-notebook.ts` |
| Result renderer | `kyuubi-server/web-ui/src/views/notebook/components/CellResult.vue` |
| UI regression tests | `kyuubi-server/web-ui/src/views/notebook/components/CellResult.test.ts` |
| Codec regression tests | `kyuubi-server/src/test/scala/org/apache/kyuubi/server/notebook/runtime/PySparkResponseCodecSuite.scala` |
| Sanitizer regression tests | `kyuubi-server/src/test/scala/org/apache/kyuubi/server/notebook/RichOutputSanitizerSuite.scala` |

## 9. Kiểm thử và tiêu chí nghiệm thu

### 9.1. Automated tests bắt buộc

1. Worker trả ít nhất hai ảnh PNG độc lập trong một cell và giữ thứ tự `print -> display -> print -> display`.
2. Object có PNG + SVG + text chỉ thành một rich event và một ảnh hiển thị. Text fallback không bị thêm thành stdout. Hai explicit display giống nhau vẫn là hai event.
3. Kiểm tra từng MIME bắt buộc bằng fixture ảnh thật nhỏ, không dùng string `base64-image` làm bằng chứng decode thành công. Có SVG với text, defs/clip path để phát hiện sanitize làm hỏng chart.
4. Rich hook trả bytes, string, tuple với metadata, `None`, exception và MIME không hỗ trợ; kiểm tra không double-encode và không chạy object expression hai lần.
5. Matplotlib: explicit show, auto-flush cuối cell, nhiều figure, display + auto-flush không lặp, figure dùng qua hai cell. Seaborn axes-level và figure-level; Pillow và IPython Image/SVG/raw display.
6. Đọc legacy text/MIME map, response có `content.data` qua engine, và envelope mới. `print()` chứa JSON giống bundle vẫn là text. Mode `AUTO` và JDBC không đổi ngoài contract đã được chấp thuận trong implementation.
7. Cursor/paging: trang có toàn payload bị lọc; hơn một trang; retry cùng cursor; terminal execution vẫn lấy đủ; chạy lại cell không nhận output cũ.
8. Payload hỏng/quá lớn, ảnh sai MIME, SVG script/event/external resource: có thông báo/fallback và không làm mất output khác; không lặp fetch vô hạn.
9. Regression: SQL table/results/logs/error, HTML/JSON output hiện có, biến Python giữa cell, cancellation/session restart và quyền đọc execution.
10. Plotly/Altair có nhóm integration test riêng khi đủ dependency. Test bị skip do thiếu exporter phải ghi rõ, không báo đã kiểm thử thành công thư viện đó.

Ưu tiên tests đi qua codec/service/component thật. Mock backend/Spark ở ranh giới phù hợp; không chỉ test lại helper bằng cùng logic implementation. Một smoke test trên notebook đã build phải chứng minh đường Python -> engine row -> REST -> UI, khi có môi trường chạy được.

### 9.2. Cell mẫu để nghiệm thu thủ công sau triển khai

Các cell sau là hành vi **cần có sau khi agent triển khai**, không khẳng định worker hiện tại đã hỗ trợ.

Matplotlib và Seaborn, dữ liệu nội bộ không cần tải dataset:

```python
import matplotlib.pyplot as plt
import seaborn as sns

fig, ax = plt.subplots()
sns.lineplot(x=[1, 2, 3], y=[3, 1, 4], ax=ax)
ax.set_title("Seaborn in notebook")
plt.show()
```

Pillow và SVG, hai output ảnh riêng biệt trong cùng cell:

```python
from PIL import Image as PILImage
from IPython.display import display, SVG

print("before images")
display(PILImage.new("RGB", (240, 100), "steelblue"))
print("between images")
display(SVG(data='<svg xmlns="http://www.w3.org/2000/svg" width="240" height="100">'
                 '<circle cx="50" cy="50" r="35" fill="orange"/>'
                 '<text x="100" y="55">SVG output</text></svg>'))
print("after images")
```

JPEG, GIF, WebP và raw MIME bundle dùng ảnh tạo tại runtime:

```python
import base64
import io
from PIL import Image as PILImage
from IPython.display import display

sample = PILImage.new("RGB", (120, 80), "seagreen")
for fmt, mime in [("JPEG", "image/jpeg"), ("GIF", "image/gif"), ("WEBP", "image/webp")]:
    buffer = io.BytesIO()
    sample.save(buffer, format=fmt)
    display({mime: base64.b64encode(buffer.getvalue()).decode("ascii")}, raw=True)
```

Plotly static export, yêu cầu exporter đã được chuẩn bị trong Spark image:

```python
import plotly.graph_objects as go
from IPython.display import Image, display

fig = go.Figure(data=go.Bar(x=["A", "B", "C"], y=[2, 5, 3]))
display(Image(data=fig.to_image(format="png")))
```

Altair static SVG, yêu cầu dependency export của phiên bản đang dùng:

```python
import io
import altair as alt
import pandas as pd
from IPython.display import SVG, display

data = pd.DataFrame({"x": [1, 2, 3], "y": [2, 4, 3]})
chart = alt.Chart(data).mark_line().encode(x="x:Q", y="y:Q")
buffer = io.StringIO()
chart.save(buffer, format="svg")
display(SVG(data=buffer.getvalue()))
```

Agent phải chạy/điều chỉnh ví dụ theo phiên bản đã cài, ghi lại expected output và dependency. Với raw bundle có cả PNG/SVG/text, thêm test chọn một representation. Với GIF động, thêm fixture animation riêng; ví dụ GIF ở trên chỉ kiểm tra ảnh GIF tĩnh.

### 9.3. Lệnh kiểm tra

Thực hiện trong WSL Debian. Xem profile/module của checkout trước khi chọn Maven flags. Các lệnh dưới là điểm bắt đầu, không thay cho kiểm tra worker và engine transport:

```bash
cd /mnt/d/DOC/VT/vtnet-kyuubi
./build/mvn -pl kyuubi-server -am test -DwildcardSuites=org.apache.kyuubi.server.notebook.runtime.PySparkResponseCodecSuite,org.apache.kyuubi.server.notebook.RichOutputSanitizerSuite
```

Chạy thêm suite mới và tests engine cho phần `PythonResponseContent`/worker đã sửa. Nếu chưa có worker test harness phù hợp, bổ sung harness cô lập Spark gateway nhưng vẫn chạy collector/serialization thật.

```bash
cd /mnt/d/DOC/VT/vtnet-kyuubi/kyuubi-server/web-ui
pnpm test --run src/views/notebook/components/CellResult.test.ts
pnpm lint
pnpm build
```

Chạy tests mới của polling/renderer nếu tách file. Formatting theo `./dev/reformat` khi chuẩn bị bàn giao/commit, kiểm tra diff để không lẫn thay đổi ngoài nhiệm vụ. Nếu môi trường thiếu JDK/dependency/Spark, báo chính xác test nào chưa chạy và nguyên nhân; không suy từ static review rằng e2e đã pass.

## 10. Bàn giao và rollout

Agent bàn giao:

- Code đã triển khai cùng danh sách file và giải thích ngắn về capture, MIME selection, paging và backward compatibility.
- Tài liệu contract mới với ví dụ worker response, engine row và REST response thực tế; ghi rõ format cũ còn hỗ trợ.
- Hướng dẫn người dùng tại `docs/notebook/VISUALIZATION.md`, bao gồm MIME allow-list, ví dụ đa thư viện, giới hạn, dependency bắt buộc/tùy chọn và cách xử lý unsupported interactive MIME.
- Bảng test pass/fail/skip, phân biệt unit test, integration test và smoke test deployment. Không khẳng định các thư viện chưa chạy là đã hỗ trợ hoàn toàn.
- Hướng dẫn rebuild **Spark engine JAR/resource Python**, Spark runtime image khi dependency đổi, **Kyuubi server** và **Web UI** khi các phần đó đổi. Chỉ rebuild UI không cập nhật Python worker nằm trong engine.
- Ghi rõ phối hợp version engine/server/UI; fallback với engine cũ có giới hạn. Worker/session đang sống có thể dùng code cũ, vì vậy rollout cần tạo runtime/session mới; restart sẽ mất biến trong session và phải được người vận hành phối hợp.

Checklist hoàn thành:

- [ ] Năm MIME ảnh bắt buộc hiển thị xuyên suốt backend/UI bằng payload hợp lệ.
- [ ] Matplotlib, Seaborn, Pillow, IPython display và custom rich-repr object hoạt động theo phạm vi đã chốt.
- [ ] Plotly/Altair có đường static export và kết quả kiểm thử/dependency rõ ràng.
- [ ] Một bundle nhiều representation không tạo ảnh trùng; một cell nhiều display event không mất ảnh/thứ tự.
- [ ] Legacy API/text/SQL vẫn hoạt động; JSON được in ra không bị hiểu thành hình.
- [ ] Lỗi, giới hạn, sandbox, cursor và rerun không gây output im lặng hoặc polling lặp.
- [ ] Tài liệu sử dụng, hướng dẫn build/rollout và kết quả xác minh đã bàn giao.
