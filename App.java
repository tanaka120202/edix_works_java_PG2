
// Webサーバー（ブラウザからの接続を受ける仕組み）を使います。
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange; // ★ 分類登録の結果を、その場で画面へ返します。
// ポート番号を指定するための住所情報を使います。
import java.net.InetSocketAddress;
import java.net.URLDecoder; // URL形式の文字を元に戻す機能を使います。
import java.net.URLEncoder; // ★ 複数の絞り込み条件を、日本語も含めてURLへ安全に書き込みます。
import java.nio.charset.StandardCharsets; // UTF-8の文字コードを指定する機能を使います。
import java.time.LocalDate; // ★ 期限を日付として確認する標準機能です。
import java.time.ZoneId; // ★ 期限の判定は日本時間で統一します。
import java.time.format.DateTimeParseException; // ★ 正しくない日付を受け止めます。
import java.sql.Connection; // ★変更 SQLiteへの接続を使います。
import java.sql.DriverManager; // ★変更 SQLiteへの接続を作ります。
import java.sql.PreparedStatement; // ★変更 SQLに値を安全に渡す文を使います。
import java.sql.ResultSet; // ★変更 SELECTの結果を読みます。
import java.sql.SQLException; // ★変更 SQLで起きるエラーを扱います。
import java.sql.Statement; // ★変更 テーブル作成のSQLを実行します。
import java.io.IOException; // ★変更 SQLエラーをWeb処理から伝えます。
import java.util.List; // 複数のTodoを並べて持つ一覧を使います。
import java.util.ArrayList; // Todo一覧を作るための機能を使います。
import java.util.Map; // ★ カテゴリの番号と名前を対応させます。
import java.util.LinkedHashMap; // ★ 読み込んだカテゴリの順序を保ちます。
import java.util.Locale; // ★ 名前の比較で英字の大文字・小文字を揃えます。
import java.text.Normalizer; // ★ 全角・半角などの違いによる重複を防ぎます。
import java.util.concurrent.Executors; // ★ 定期的に自動削除を実行します。
import java.util.concurrent.ScheduledExecutorService; // ★ 定期処理を動かすための標準機能です。
import java.util.concurrent.TimeUnit; // ★ 定期処理の間隔を分で指定します。
// プログラムの名前（クラス名）は App です。

public class App {
    // ★変更 SQLiteデータベースの接続先です。
    static final String DB_URL = "jdbc:sqlite:todos.db";

    // ★ 各項目に複数の条件を持ち、選んでいない項目は「すべて」として扱います。
    static final String[][] FILTER_FIELDS = {
            { "statusFilter", "ステータス" }, { "priorityFilter", "重要度" },
            { "deadlineFilter", "期限" }, { "filter", "カテゴリ" },
            { "locationFilter", "場所" }, { "personFilter", "関係者" },
            { "tagFilter", "タグ" }, { "projectFilter", "プロジェクト" }
    };

    static class Filters {
        final Map<String, List<String>> values = new LinkedHashMap<>();

        List<String> get(String key) {
            return values.getOrDefault(key, List.of());
        }

        boolean active() {
            for (List<String> selected : values.values()) {
                if (!selected.isEmpty()) return true;
            }
            return false;
        }

        // ★ 同じ項目名を繰り返して、複数の選択を欠けずに引き継ぎます。
        String query() {
            List<String> fields = new ArrayList<>();
            for (String[] field : FILTER_FIELDS) {
                for (String value : get(field[0])) {
                    fields.add(field[0] + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
                }
            }
            return String.join("&", fields);
        }

        String hiddenInputs() {
            String html = "";
            for (String[] field : FILTER_FIELDS) {
                for (String value : get(field[0])) {
                    html += "<input type='hidden' name='" + field[0] + "' value='" + escapeHtml(value) + "'>";
                }
            }
            return html;
        }
    }

    // ★ 場所・関係者・プロジェクトの名前と保存先をまとめます（enumは決まった種類の一覧です）。
    enum Detail {
        LOCATION("location", "locations", "location_id", "場所", "自宅・会社など"),
        PERSON("person", "people", "person_id", "関係者", "田中さん・佐藤さんなど"),
        PROJECT("project", "projects", "project_id", "プロジェクト", "新商品・引っ越しなど");

        final String key;
        final String table;
        final String column;
        final String label;
        final String placeholder;

        Detail(String key, String table, String column, String label, String placeholder) {
            this.key = key;
            this.table = table;
            this.column = column;
            this.label = label;
            this.placeholder = placeholder;
        }

        String filterKey() {
            return key + "Filter";
        }
    }

    // ★ 既存の5種類の保存先を共通の分類UIから扱います。表や列の名前はここで固定します。
    enum Classification {
        CATEGORY("category", "genres", "genre_id", "genre", "カテゴリ", "未分類"),
        LOCATION("location", "locations", "location_id", "location", "場所", "未設定"),
        PERSON("person", "people", "person_id", "person", "関係者", "未設定"),
        PROJECT("project", "projects", "project_id", "project", "プロジェクト", "未設定"),
        TAG("tag", "tags", null, "tag", "タグ", "");

        final String key, table, column, formName, label, emptyLabel;

        Classification(String key, String table, String column, String formName, String label, String emptyLabel) {
            this.key = key;
            this.table = table;
            this.column = column;
            this.formName = formName;
            this.label = label;
            this.emptyLabel = emptyLabel;
        }
    }

    // ★ 分類の番号・名前・使用中のTodo件数を一組にします（recordは値をまとめる書き方です）。
    record ClassificationItem(int id, String name, int uses) { }

    // プログラムの実行が始まる場所で、例外（予期しない問題）を呼び出し元に伝えます。
    public static void main(String[] args) throws Exception {
        // ★変更 todos表を用意します。表はデータを行と列で保存する場所です。
        initDatabase();
        // ★ 期限切れをフォルダへ移し、移動から30日たったものだけ削除します。
        maintainExpiredTodos();
        // 8080番ポート（接続口）で待ち受けるサーバーを用意します。
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        // ブラウザが「/」にアクセスしたときの処理を登録します。
        server.createContext("/", exchange -> {
            // ブラウザがアクセスしたパス（URLの末尾）を取り出します。
            String path = exchange.getRequestURI().getPath();
            // GETやPOSTなど、送信方法を取り出します。
            String method = exchange.getRequestMethod();
            // 通常の応答はプレーンテキスト（飾りのない文字）として返します。
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            // 返す文字を入れる場所を用意します。
            String message;
            // /addへのPOST（フォーム送信）ならTodoをデータベースへ追加します。
            if (path.equals("/classifications") && method.equals("GET")) {
                Classification kind = classificationForKey(formValue(exchange.getRequestURI().getRawQuery(), "type"));
                if (kind == null) kind = Classification.CATEGORY;
                String query = exchange.getRequestURI().getRawQuery();
                message = renderClassificationManagement(kind, formValue(query, "notice"), readFilters(query), formValue(query, "folder"));
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            } else if ((path.equals("/classifications/rename") || path.equals("/classifications/delete"))
                    && method.equals("POST")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Classification kind = classificationForKey(formValue(body, "type"));
                String notice;
                try {
                    if (kind == null) throw new IllegalArgumentException();
                    int id = Integer.parseInt(formValue(body, "id"));
                    if (path.endsWith("/rename")) {
                        renameClassification(kind, id, formValue(body, "name"));
                        notice = "renamed";
                    } else {
                        deleteClassification(kind, id);
                        notice = "deleted";
                    }
                } catch (IllegalArgumentException e) {
                    notice = "invalid";
                } catch (IOException e) {
                    notice = "error";
                }
                exchange.getResponseHeaders().set("Location", classificationLocation(
                        kind == null ? Classification.CATEGORY : kind, readFilters(body), formValue(body, "folder"), notice));
                exchange.sendResponseHeaders(303, -1);
                exchange.close();
                return;
            } else if (path.equals("/add") && method.equals("POST")) {
                // 送信された中身をUTF-8の文字として読み取ります。
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                // ★ 複数の入力欄から、やることとカテゴリを取り出します。
                String title = formValue(body, "todo");
                try {
                    int genreId = readGenreId(formValue(body, "genre"));
                    String priority = formValue(body, "priority");
                    if (priority.isEmpty()) {
                        priority = "中"; // ★ 重要度を送らない従来の追加操作にも対応します。
                    }
                    if (!title.isEmpty() && isValidPriority(priority)
                            && (genreId == 0 || loadGenres().containsKey(genreId))) {
                        // ★ 新しい3項目も、登録済みの番号か確認して一緒に保存します。
                        addTodo(title, genreId, priority, readDetailIds(body), readTagIds(body));
                    }
                } catch (NumberFormatException e) {
                    // ★ 不正な分類番号やタグ番号では追加しません。
                }
                // 追加後にトップページへ戻る場所を指定します。
                exchange.getResponseHeaders().set("Location",
                        listLocation(readFilters(body), formValue(body, "folder")));
                // 303でトップページへ移動する応答を返します。
                exchange.sendResponseHeaders(303, -1);
                // このリクエストへの応答を閉じます。
                exchange.close();
                // この分岐の処理を終えます。
                return;
                // ★ 自由な名前のカテゴリを登録します。
            } else if (path.equals("/genres/add") && method.equals("POST")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String name = formValue(body, "name").strip();
                if (!name.isEmpty()) {
                    addGenre(name);
                }
                exchange.getResponseHeaders().set("Location",
                        listLocation(readFilters(body), formValue(body, "folder")));
                exchange.sendResponseHeaders(303, -1);
                exchange.close();
                return;
                // ★ タグの名前を登録します。同じ名前は重複登録しません。
            } else if (path.equals("/tags/add") && method.equals("POST")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String name = formValue(body, "name").strip();
                if (!name.isEmpty()) {
                    addTag(name);
                }
                exchange.getResponseHeaders().set("Location",
                        listLocation(readFilters(body), formValue(body, "folder")));
                exchange.sendResponseHeaders(303, -1);
                exchange.close();
                return;
                // ★ 選んだ複数のタグで、そのTodoのタグ設定を置き換えます。
            } else if (path.equals("/tags") && method.equals("POST")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                try {
                    int id = Integer.parseInt(formValue(body, "id"));
                    updateTags(id, readTagIds(body));
                } catch (NumberFormatException e) {
                    // ★ 不正な番号が含まれるときは、タグ設定を変更しません。
                }
                exchange.getResponseHeaders().set("Location",
                        listLocation(readFilters(body), formValue(body, "folder")));
                exchange.sendResponseHeaders(303, -1);
                exchange.close();
                return;
                // ★ 既存Todoのカテゴリを変更します。
            } else if (path.equals("/genre") && method.equals("POST")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                try {
                    int id = Integer.parseInt(formValue(body, "id"));
                    int genreId = readGenreId(formValue(body, "genre"));
                    if (genreId == 0 || loadGenres().containsKey(genreId)) {
                        updateGenre(id, genreId);
                    }
                } catch (NumberFormatException e) {
                    // ★ 不正な番号では変更しません。
                }
                exchange.getResponseHeaders().set("Location",
                        listLocation(readFilters(body), formValue(body, "folder")));
                exchange.sendResponseHeaders(303, -1);
                exchange.close();
                return;
                // ★ カテゴリと同じように、場所・関係者・プロジェクトを登録します。
            } else if (detailForPath(path, true) != null && method.equals("POST")) {
                Detail detail = detailForPath(path, true);
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String name = formValue(body, "name").strip();
                if (!name.isEmpty()) {
                    addDetail(detail, name);
                }
                exchange.getResponseHeaders().set("Location",
                        listLocation(readFilters(body), formValue(body, "folder")));
                exchange.sendResponseHeaders(303, -1);
                exchange.close();
                return;
                // ★ 既存Todoの場所・関係者・プロジェクトを変更します。
            } else if (detailForPath(path, false) != null && method.equals("POST")) {
                Detail detail = detailForPath(path, false);
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                try {
                    int id = Integer.parseInt(formValue(body, "id"));
                    int choiceId = readDetailId(detail, formValue(body, detail.key));
                    updateDetail(id, detail, choiceId);
                } catch (NumberFormatException e) {
                    // ★ 不正な番号では変更しません。
                }
                exchange.getResponseHeaders().set("Location",
                        listLocation(readFilters(body), formValue(body, "folder")));
                exchange.sendResponseHeaders(303, -1);
                exchange.close();
                return;
                // ★ Todoごとのフォームから重要度を変更します。
            } else if (path.equals("/priority") && method.equals("POST")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                try {
                    int id = Integer.parseInt(formValue(body, "id"));
                    String priority = formValue(body, "priority");
                    if (isValidPriority(priority)) {
                        updatePriority(id, priority);
                    }
                } catch (NumberFormatException e) {
                    // ★ 不正な番号では変更しません。
                }
                exchange.getResponseHeaders().set("Location",
                        listLocation(readFilters(body), formValue(body, "folder")));
                exchange.sendResponseHeaders(303, -1);
                exchange.close();
                return;
                // ★ Todoごとのフォームから期限を保存します。
            } else if (path.equals("/deadline") && method.equals("POST")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                try {
                    int id = Integer.parseInt(formValue(body, "id"));
                    String deadline = formValue(body, "deadline");
                    // ★ 空欄なら期限を解除し、日付なら正しい年月日だけを保存します。
                    if (deadline.isEmpty() || deadline.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                        updateDeadline(id, deadline.isEmpty() ? null : LocalDate.parse(deadline).toString());
                    }
                } catch (NumberFormatException | DateTimeParseException e) {
                    // ★ 番号や日付が不正なら保存せず一覧へ戻ります。
                }
                exchange.getResponseHeaders().set("Location",
                        listLocation(readFilters(body), formValue(body, "folder")));
                exchange.sendResponseHeaders(303, -1);
                exchange.close();
                return;
                // ★ 期限切れフォルダのTodoを通常の一覧に戻します。
            } else if (path.equals("/restore") && method.equals("POST")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                try {
                    int id = Integer.parseInt(formValue(body, "id"));
                    restoreTodo(id);
                } catch (NumberFormatException e) {
                    // ★ 不正な番号では変更しません。
                }
                exchange.getResponseHeaders().set("Location", listLocation(readFilters(body), ""));
                exchange.sendResponseHeaders(303, -1);
                exchange.close();
                return;
                // /doneへのGET（ページを開く操作）でTodoを完了にします。
            } else if (path.equals("/done") && method.equals("GET")) {
                // URLの ? 以降からidを受け取ります。
                String query = exchange.getRequestURI().getRawQuery();
                // idがあり、数字部分が空でないときだけ処理します。
                if (!formValue(query, "id").isEmpty()) {
                    try {
                        // idを数に変えます。
                        int id = Integer.parseInt(formValue(query, "id"));
                        // ★変更 idが一致するTodoをUPDATE（既存行の変更）で完了にします。
                        markTodoDone(id);
                    } catch (NumberFormatException e) {
                        // 数字でないidは無視してトップページへ戻します。
                    }
                }
                // 処理後にトップページへ戻る場所を指定します。
                exchange.getResponseHeaders().set("Location",
                        listLocation(readFilters(query), formValue(query, "folder")));
                exchange.sendResponseHeaders(303, -1);
                exchange.close();
                return;
                // /deleteへのGETでTodoを削除します。
            } else if (path.equals("/delete") && method.equals("GET")) {
                // URLの ? 以降からidを受け取ります。
                String query = exchange.getRequestURI().getRawQuery();
                // idがあり、数字部分が空でないときだけ処理します。
                if (!formValue(query, "id").isEmpty()) {
                    try {
                        // idを数に変えます。
                        int id = Integer.parseInt(formValue(query, "id"));
                        // ★変更 idが一致するTodoをDELETE（行の削除）で消します。
                        deleteTodo(id);
                    } catch (NumberFormatException e) {
                        // 数字でないidは無視してトップページへ戻します。
                    }
                }
                // 処理後にトップページへ戻る場所を指定します。
                exchange.getResponseHeaders().set("Location",
                        listLocation(readFilters(query), formValue(query, "folder")));
                exchange.sendResponseHeaders(303, -1);
                exchange.close();
                return;
                // / を開いたら入力フォームとTodo一覧を表示します。
            } else if (path.equals("/")) {
                // ★ カテゴリと絞り込み条件を読み込みます。
                Map<Integer, String> genres = loadGenres();
                String query = exchange.getRequestURI().getRawQuery();
                Map<Detail, Map<Integer, String>> details = loadDetails();
                Map<Integer, String> tags = loadTags();
                Filters filters = readFilters(query);
                retainKnownFilters(filters, filterOptions(genres, details, tags));
                boolean expiredFolder = formValue(query, "folder").equals("expired");
                List<Todo> todos = loadTodos(filters, expiredFolder ? "expired" : "");
                // ★ 表示の組み立てだけをまとめ、既存のフォームと送信先を使います。
                message = renderPage(todos, genres, filters, expiredFolder, details, tags);
                // トップページの応答はHTMLとして扱うよう伝えます。
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                // 上記以外のパスへの返事を用意します。
            } else {
                message = "ページが見つかりません";
            }
            // 返す文字をUTF-8のバイト列（送信できるデータ）に変えます。
            byte[] body = message.getBytes("UTF-8");
            // 成功を表す200と、送るデータの長さを伝えます。
            exchange.sendResponseHeaders(200, body.length);
            // 用意したデータをブラウザへ送ります。
            exchange.getResponseBody().write(body);
            // 送信が終わったことを伝えて、応答を閉じます。
            exchange.getResponseBody().close();
        });
        // ★ 入力途中のTodoを送信せず、分類だけを登録して番号と名前を受け取ります。
        server.createContext("/api/classifications", exchange -> {
            if (!exchange.getRequestURI().getPath().equals("/api/classifications/create")) {
                sendJson(exchange, 404, "{\"error\":\"ページが見つかりません\"}");
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.getResponseHeaders().set("Allow", "POST");
                sendJson(exchange, 405, "{\"error\":\"POSTで送信してください\"}");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                Classification kind = classificationForKey(formValue(body, "type"));
                if (kind == null) throw new IllegalArgumentException();
                ClassificationItem item = createClassification(kind, formValue(body, "name"));
                sendJson(exchange, 200, "{\"id\":" + item.id() + ",\"name\":\"" + escapeJson(item.name()) + "\"}");
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, "{\"error\":\"分類名を入力してください。\"}");
            } catch (IOException e) {
                sendJson(exchange, 500, "{\"error\":\"登録できませんでした。もう一度お試しください。\"}");
            }
        });
        // ★ GET /api/todosで全TodoをJSON形式（データ交換用の文字列）で返します。
        server.createContext("/api/todos", exchange -> { // ★ JSON用の入口を登録します。
            // ★ SQLiteからTodo一覧を読み込みます。
            List<Todo> todos = loadTodos(); // ★ 一覧を取得します。
            // ★ 一覧をJSON文字列に変換します。
            String json = todosToJson(todos); // ★ JSONの本文を用意します。
            // ★ Content-Typeを指定どおりに設定します。
            exchange.getResponseHeaders().set("Content-Type", "application/json"); // ★ charsetは付けません。
            // ★ JSONをUTF-8の送信データに変換します。
            byte[] body = json.getBytes(StandardCharsets.UTF_8); // ★ JSON本文をバイト列にします。
            // ★ 成功を表す200と本文の長さを送ります。
            exchange.sendResponseHeaders(200, body.length); // ★ 応答を開始します。
            // ★ JSON本文をブラウザへ送ります。
            exchange.getResponseBody().write(body); // ★ 本文を書き込みます。
            // ★ 応答を閉じます。
            exchange.getResponseBody().close(); // ★ 送信を終えます。
        }); // ★ JSON用の入口を登録し終えます。
        // サーバーの待ち受けを開始します。
        server.start();
        // ★ アクセスがない間も、1分ごとに期限切れの移動と保存期間を確認します。
        startExpiryCleanup();
        // 起動したことと停止方法をターミナルに表示します。
        System.out.println("サーバー起動: http://localhost:8080 （止めるときは Ctrl+C）");
    }

    // ★ 画面だけを組み立てます。フォームの名前・送信先・保存処理は従来どおりです。
    static String renderPage(List<Todo> todos, Map<Integer, String> genres, Filters filters, boolean expiredFolder,
            Map<Detail, Map<Integer, String>> details, Map<Integer, String> tags) {
        String folder = expiredFolder ? "expired" : "";
        String hiddenFilter = "<input type='hidden' name='folder' value='" + folder + "'>" + filters.hiddenInputs();
        String filterQuery = filters.query().isEmpty() ? "" : "&amp;" + escapeHtml(filters.query());
        Map<String, Map<String, String>> options = filterOptions(genres, details, tags);
        int selectedGenre = singleFilterId(filters, "filter");
        List<String> context = new ArrayList<>();
        for (String[] field : FILTER_FIELDS) {
            if (!filters.get(field[0]).isEmpty()) {
                context.add(field[1] + "：" + filterSelectionLabel(filters.get(field[0]), options.get(field[0])));
            }
        }
        String currentContext = context.isEmpty() ? "すべて" : String.join(" / ", context);
        String html = "<!DOCTYPE html><html lang='ja'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1'><title>"
                + (expiredFolder ? "期限切れ | ToDoリスト" : "ToDo") + "</title>";
        // ★ 指定フォントを読み込み、読み込めない場合は端末の標準フォントを使います。
        html += "<link rel='preconnect' href='https://fonts.googleapis.com'><link rel='preconnect' href='https://fonts.gstatic.com' crossorigin><link href='https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&amp;family=Noto+Sans+JP:wght@400;500;600&amp;display=swap' rel='stylesheet'><style>"
                + pageStyles() + "</style></head><body><div class='workspace'>";
        // ★ サイドバーはナビゲーションと分類管理への入口にします。
        html += "<aside class='sidebar'><div class='brand'>" + uiIcon("list")
                + "<span>ToDoリスト</span></div><div class='sidebar-body'>";
        html += "<nav class='folder-navigation' aria-label='Todoのフォルダ'><a class='folder-link"
                + (!expiredFolder ? " is-selected" : "") + "'" + (!expiredFolder ? " aria-current='page'" : "")
                + " href='" + escapeHtml(listLocation(filters, "")) + "'>" + uiIcon("list")
                + "Todo一覧</a><a class='folder-link" + (expiredFolder ? " is-selected" : "") + "'"
                + (expiredFolder ? " aria-current='page'" : "") + " href='"
                + escapeHtml(listLocation(filters, "expired")) + "'>" + uiIcon("folder") + "期限切れ</a></nav>";
        // ★ 既存の絞り込みは、ポップアップのフィルタータブへ移します。
        html += "<div class='sidebar-section'><button type='button' class='button button-secondary filter-trigger' onclick=\"openOrganize('filter')\" aria-haspopup='dialog' aria-controls='organize-dialog'>"
                + uiIcon("filter") + "フィルター</button></div>";
        String filterForm = filterAccordionForm(filters, options, folder);
        html += classificationNav(null, filters, folder) + "</div></aside>";
        // ★ 右側は最大860pxの余白を持つメイン画面です。
        html += "<main class='main-pane'><div class='content'><header class='page-header'><p class='page-context'>"
                + escapeHtml(currentContext) + "</p><h1>" + (expiredFolder ? "期限切れ" : "ToDoリスト") + "</h1>";
        if (expiredFolder) {
            html += "<p class='retention-note'>移動した日から30日後に自動削除されます。リストに戻すと期限は未登録になります。</p>";
        }
        html += "</header>";
        if (!expiredFolder) {
            List<Integer> selectedTags = new ArrayList<>();
            for (String id : filters.get("tagFilter")) selectedTags.add(Integer.parseInt(id));
            // ★ 通常はタスク名と小さな設定チップだけを表示し、追加を独立した主操作にします。
            html += "<section class='add-section' aria-labelledby='add-heading'><h2 id='add-heading' class='small-heading'>ToDoを追加</h2>"
                    + "<form id='todo-add-form' class='add-form quick-add-form' method='post' action='/add'>"
                    + "<label class='field task-name-field'><span class='sr-only'>やること</span><input name='todo' placeholder='やることを入力'></label>"
                    + "<div class='quick-add-controls'><div class='quick-settings'>"
                    + "<div class='quick-setting' data-quick-setting='category'><button type='button' id='quick-category-button' class='setting-chip' aria-expanded='false' aria-controls='quick-category-editor'>"
                    + "<span class='chip-label'>カテゴリ：" + escapeHtml(selectedGenre == 0 ? "未分類" : genres.get(selectedGenre))
                    + "</span></button>"
                    + "<div id='quick-category-editor' class='quick-editor' role='group' aria-label='カテゴリを変更' hidden></div></div>"
                    + "<div class='quick-setting' data-quick-setting='priority'><button type='button' id='quick-priority-button' class='setting-chip' aria-expanded='false' aria-haspopup='listbox' aria-controls='quick-priority-editor'>"
                    + "<span class='chip-label'>重要度：中</span></button>"
                    + "<div id='quick-priority-editor' class='quick-editor priority-editor' role='listbox' aria-label='重要度' hidden>";
            for (String priority : List.of("高", "中", "低")) {
                html += "<button type='button' role='option' data-priority='" + priority + "' aria-selected='"
                        + priority.equals("中") + "' tabindex='-1'>" + priority + "</button>";
            }
            html += "</div></div><button type='button' id='todo-details-toggle' class='details-toggle' aria-expanded='false' aria-controls='todo-add-details'>"
                    + "詳細設定</button></div>"
                    + "<button type='submit' class='button button-primary add-submit'>" + uiIcon("plus") + "追加</button></div>"
                    + "<div class='configured-settings' aria-label='設定済みの項目' hidden></div>"
                    + "<div id='todo-add-details' class='todo-add-details' role='region' aria-labelledby='todo-details-toggle' hidden><div class='add-details-grid'>"
                    + "<div class='detail-slot' data-editor-slot='category'><div class='field' data-add-setting='category'><span class='field-label'>カテゴリ</span>"
                    + genreSelect(genres, selectedGenre) + "</div></div>"
                    + "<div class='detail-slot'><label class='field' data-add-setting='priority'><span class='field-label'>重要度</span>"
                    + prioritySelect("中") + "</label></div><div class='detail-slot' data-add-setting='tag'>" + tagPicker(tags, selectedTags) + "</div>";
            for (Detail detail : Detail.values()) {
                int selectedId = singleFilterId(filters, detail.filterKey());
                html += "<div class='detail-slot'><div class='field' data-add-setting='" + detail.key + "'><span class='field-label'>" + detail.label + "</span>"
                        + detailSelect(detail, details.get(detail), selectedId) + "</div></div>";
            }
            html += "</div></div>" + hiddenFilter + "</form></section>";
        }
        // ★ 一覧の並び替えは、ポップアップで選んで決定する形にします。
        html += "<section class='list-section" + (expiredFolder ? " archived-list" : "") + "' aria-labelledby='list-heading'><div class='list-toolbar'><h2 id='list-heading'>一覧</h2><div class='list-order'><span id='current-sort' class='current-sort' aria-live='polite'>期限・昇順</span><button type='button' class='button button-secondary sort-trigger' onclick=\"openOrganize('sort')\" aria-haspopup='dialog' aria-controls='organize-dialog'>"
                + uiIcon("sort") + "並び替え</button></div></div>";
        html += "<div class='column-headings' aria-hidden='true'><span>やること</span><span>完了状態</span><span>重要度</span><span>期限</span></div><ul id='todo-list' class='todo-list'>";
        String todoDialogs = ""; // ★ 分類の編集欄は、一覧の外にある詳細ポップアップへまとめます。
        if (todos.isEmpty()) {
            html += "<li class='empty-state'>"
                    + (expiredFolder ? "期限切れのTodoはありません"
                            : !filters.active()
                                    ? "やることは、いまゼロです" : "この条件のTodoはありません")
                    + "</li>";
        } else {
            for (Todo todo : todos) {
                String hiddenId = "<input type='hidden' name='id' value='" + todo.getId() + "'>";
                // ★ 保存済みの値を並び替えに使います。入力途中のフォームはそのまま保持します。
                html += "<li class='task-row' data-added='" + todo.getId() + "' data-deadline='" + escapeHtml(todo.getDeadline())
                        + "' data-priority='" + escapeHtml(todo.getPriority())
                        + "' data-category='" + escapeHtml(todo.getGenreId() == 0 ? "" : todo.getGenreName())
                        + "'><div class='task-title-box'><button type='button' class='task-title todo-detail-trigger'"
                        + " aria-haspopup='dialog' aria-controls='todo-dialog-" + todo.getId() + "'>"
                        + escapeHtml(todo.getTitle()) + "</button></div>";
                // ★ 未完了の表示から、従来と同じ完了処理を呼び出せます。
                html += "<div class='task-status'>" + (todo.isDone()
                        ? "<span class='completion-mark'>✔ 完了</span>"
                        : expiredFolder ? "<span class='unfinished-status'>未完了</span>"
                                : "<a class='complete-link' href='/done?id=" + todo.getId() + filterQuery
                                        + "' aria-label='完了にする' title='クリックして完了にする'>未完了</a>") + "</div>";
                if (expiredFolder) {
                    html += "<span class='task-priority archived-value'>" + escapeHtml(todo.getPriority())
                            + "</span><span class='task-deadline archived-value'>" + escapeHtml(todo.getDeadline())
                            + "</span>";
                } else {
                    html += "<form class='task-field task-priority' data-autosave method='post' action='/priority' aria-label='重要度の変更'>"
                            + hiddenId + hiddenFilter + "<label class='control-label'><span class='sr-only'>重要度</span>"
                            + prioritySelect(todo.getPriority())
                            + "</label></form>";
                    html += "<form class='task-field task-deadline' data-autosave method='post' action='/deadline' aria-label='期限の変更'>"
                            + hiddenId + hiddenFilter
                            + "<label class='control-label'><span class='sr-only'>期限</span><input type='date' name='deadline' max='9999-12-31' value='"
                            + escapeHtml(todo.getDeadline())
                            + "'></label></form>";
                }
                html += "</li>";
                todoDialogs += "<dialog id='todo-dialog-" + todo.getId() + "' class='todo-detail-dialog'"
                        + " aria-labelledby='todo-dialog-title-" + todo.getId() + "'><div class='todo-dialog-content'><div class='todo-dialog-header'>"
                        + "<h2 id='todo-dialog-title-" + todo.getId() + "'>" + escapeHtml(todo.getTitle())
                        + "</h2><button type='button' class='button button-secondary todo-dialog-close' autofocus>閉じる</button></div>";
                if (expiredFolder) {
                    todoDialogs += "<p class='task-note'>自動削除：" + LocalDate.parse(todo.getExpiredOn()).plusDays(30) + "</p>";
                }
                todoDialogs += "<div class='todo-dialog-fields'>";
                if (expiredFolder) {
                    todoDialogs += "<div class='detail-value'><span class='field-label'>カテゴリ</span><span class='archived-value'>"
                            + escapeHtml(todo.getGenreId() == 0 ? "未分類" : todo.getGenreName()) + "</span></div>";
                } else {
                    todoDialogs += "<form class='task-detail' data-autosave method='post' action='/genre' aria-label='カテゴリの変更'>"
                            + hiddenId + hiddenFilter + "<div class='field'><span class='field-label'>カテゴリ</span>"
                            + genreSelect(genres, todo.getGenreId())
                            + "</div></form>";
                }
                for (Detail detail : Detail.values()) {
                    if (expiredFolder) {
                        todoDialogs += "<div class='detail-value'><span class='field-label'>" + detail.label
                                + "</span><span class='archived-value'>"
                                + escapeHtml(todo.getDetailId(detail.key) == 0 ? "未設定" : todo.getDetailName(detail.key))
                                + "</span></div>";
                    } else {
                        todoDialogs += "<form class='task-detail' data-autosave method='post' action='/" + detail.key
                                + "' aria-label='" + detail.label + "の変更'>" + hiddenId + hiddenFilter
                                + "<div class='field'><span class='field-label'>" + detail.label + "</span>"
                                + detailSelect(detail, details.get(detail), todo.getDetailId(detail.key))
                                + "</div></form>";
                    }
                }
                if (expiredFolder) {
                    todoDialogs += "<div class='task-tags'><div class='detail-value'><span class='field-label'>タグ</span>"
                            + tagNames(todo.getTags()) + "</div></div>";
                } else {
                    todoDialogs += "<form class='task-tags' data-autosave method='post' action='/tags' aria-label='タグの変更'>"
                            + hiddenId + hiddenFilter + tagPicker(tags, new ArrayList<>(todo.getTags().keySet()))
                            + "</form>";
                }
                todoDialogs += "</div><div class='todo-dialog-actions'>"
                        + (expiredFolder ? "<form class='restore-form' method='post' action='/restore'>" + hiddenId + hiddenFilter
                                + "<button class='restore-button' title='期限を未登録にして通常の一覧に戻します'>リストに戻す</button></form>" : "")
                        + "<a class='delete-link' href='/delete?id=" + todo.getId() + "&amp;folder=" + folder + filterQuery
                        + "' aria-label='削除' title='削除'>" + uiIcon("trash") + "<span>削除</span></a></div></div></dialog>";
            }
        }
        html += "</ul></section></div></main></div>";
        html += todoDialogs + organizeDialog(filterForm) + organizeScript()
                + classificationBootstrap(genres, details, tags) + classificationScript() + quickAddScript() + todoDetailScript() + autoSaveScript() + "</body></html>";
        return html;
    }

    // ★ 既存の保存先へ通信し、画面や詳細ポップアップを開いたまま編集内容を保存します。
    static String autoSaveScript() {
        return """
                <script>
                (() => {
                    let queue = Promise.resolve();
                    const list = document.getElementById('todo-list');
                    document.querySelectorAll('form[data-autosave]').forEach(form => {
                        const serialize = () => new URLSearchParams(new FormData(form)).toString();
                        const state = {saved: serialize(), queued: serialize(), revision: 0, timer: null};
                        const feedback = document.createElement('div'); feedback.className = 'autosave-feedback';
                        const status = document.createElement('span'); status.className = 'autosave-status';
                        status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite');
                        const retry = document.createElement('button'); retry.type = 'button'; retry.className = 'autosave-retry';
                        retry.textContent = '再試行'; retry.hidden = true;
                        feedback.append(status, retry); form.append(feedback);

                        function save() {
                            clearTimeout(state.timer);
                            if (!form.checkValidity()) return;
                            const body = serialize();
                            if (body === state.queued) return;
                            state.queued = body;
                            const revision = ++state.revision;
                            retry.hidden = true; status.textContent = '保存中…';
                            feedback.classList.remove('is-error'); form.setAttribute('aria-busy', 'true');
                            // 連続して変更した場合も、変更した順番で保存します。
                            queue = queue.then(async () => {
                                try {
                                    const response = await fetch(form.action, {method: 'POST', keepalive: true,
                                        headers: {'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'}, body});
                                    if (!response.ok) throw new Error('save failed');
                                    const page = new DOMParser().parseFromString(await response.text(), 'text/html');
                                    const savedList = page.getElementById('todo-list');
                                    if (!savedList) throw new Error('invalid response');
                                    state.saved = body;
                                    // 保存先が返した一覧で、並び替え用の値とフィルターへの一致を更新します。
                                    const id = new URLSearchParams(body).get('id');
                                    const selector = 'li[data-added="' + CSS.escape(id) + '"]';
                                    const row = list.querySelector(selector), savedRow = savedList.querySelector(selector);
                                    if (row) {
                                        row.hidden = !savedRow;
                                        if (savedRow) Object.assign(row.dataset, savedRow.dataset);
                                    }
                                    let empty = list.querySelector('.empty-state');
                                    if (!list.querySelector('li[data-added]:not([hidden])')) {
                                        if (!empty) { empty = document.createElement('li'); empty.className = 'empty-state'; list.append(empty); }
                                        empty.textContent = savedList.querySelector('.empty-state')?.textContent || 'この条件のTodoはありません';
                                    } else if (empty) empty.remove();
                                    sortTodoRows(appliedSort.key, appliedSort.direction);
                                    if (revision === state.revision) status.textContent = '保存済み';
                                } catch (error) {
                                    if (revision === state.revision) {
                                        state.queued = state.saved; status.textContent = '保存できませんでした';
                                        feedback.classList.add('is-error'); retry.hidden = false;
                                    }
                                } finally {
                                    if (revision === state.revision) form.removeAttribute('aria-busy');
                                }
                            });
                        }
                        retry.addEventListener('click', () => { state.queued = null; save(); });
                        form.addEventListener('classificationchange', save);
                        form.addEventListener('change', event => {
                            if (event.target.matches('input[type="date"]')) {
                                // 日付の入力途中で保存せず、入力が落ち着いてから保存します。
                                clearTimeout(state.timer); state.timer = setTimeout(save, 500);
                            } else if (event.target.matches('select')) save();
                        });
                        form.addEventListener('submit', event => { event.preventDefault(); save(); });
                    });
                })();
                </script>
                """;
    }

    // ★ Todo名を押して詳細を開き、閉じたあとは元のTodo名へフォーカスを戻します。
    static String todoDetailScript() {
        return """
                <script>
                (() => {
                    document.querySelectorAll('.todo-detail-trigger').forEach(trigger => {
                        const dialog = document.getElementById(trigger.getAttribute('aria-controls'));
                        trigger.addEventListener('click', () => dialog.showModal());
                        dialog.querySelector('.todo-dialog-close').addEventListener('click', () => dialog.close());
                        dialog.addEventListener('click', event => { if (event.target === dialog) dialog.close(); });
                        dialog.addEventListener('close', () => {
                            dialog.querySelectorAll('.classification-combo').forEach(root => root.dispatchEvent(new Event('classification-close')));
                            if (!trigger.closest('.task-row').hidden) trigger.focus({preventScroll: true});
                            else { const heading = document.getElementById('list-heading'); heading.tabIndex = -1; heading.focus({preventScroll: true}); }
                        });
                    });
                })();
                </script>
                """;
    }

    // ★ 保存形式やフォームの項目名は変えず、見た目を検索付きの共通選択欄にします。
    static String classificationCombo(Classification kind, Map<Integer, String> options, List<Integer> selected) {
        List<String> ids = new ArrayList<>();
        String hidden = "";
        for (int id : selected) {
            if (options.containsKey(id)) {
                ids.add(Integer.toString(id));
                hidden += "<input type='hidden' name='" + kind.formName + "' value='" + id + "'>";
            }
        }
        boolean multiple = kind == Classification.TAG;
        if (!multiple && ids.isEmpty()) hidden = "<input type='hidden' name='" + kind.formName + "' value='0'>";
        String value = multiple || ids.isEmpty() ? "" : options.get(Integer.parseInt(ids.get(0)));
        return "<div class='classification-combo" + (multiple ? " is-multiple" : "") + "' data-kind='" + kind.key
                + "' data-selected='" + String.join(",", ids) + "'><div class='combo-chips'></div>"
                + "<div class='combo-entry'><input class='combo-input' type='text' role='combobox' aria-label='"
                + kind.label + "' aria-autocomplete='list' aria-haspopup='listbox' aria-expanded='false' autocomplete='off' value='"
                + escapeHtml(value) + "' placeholder='" + (multiple ? "タグを検索・新規登録" : kind.emptyLabel + "・検索")
                + "'></div><div class='combo-popup' hidden>"
                + "<div class='combo-list' role='listbox' aria-label='" + kind.label + "の候補'"
                + (multiple ? " aria-multiselectable='true'" : "") + "></div><p class='combo-empty' hidden></p></div>"
                + "<div class='combo-values'>" + hidden + "</div><p class='combo-notice' role='status' aria-live='polite'></p></div>";
    }

    // ★ 分類データをJavaScriptへ渡します。HTMLとして解釈される文字は文字コードに置き換えます。
    static String classificationBootstrap(Map<Integer, String> genres, Map<Detail, Map<Integer, String>> details,
            Map<Integer, String> tags) {
        String json = "[";
        for (Classification kind : Classification.values()) {
            Map<Integer, String> options = kind == Classification.CATEGORY ? genres
                    : kind == Classification.TAG ? tags : details.get(Detail.valueOf(kind.name()));
            if (!json.equals("[")) json += ",";
            json += "{\"key\":\"" + kind.key + "\",\"label\":\"" + kind.label + "\",\"formName\":\""
                    + kind.formName + "\",\"emptyLabel\":\"" + kind.emptyLabel + "\",\"items\":[";
            boolean first = true;
            for (Map.Entry<Integer, String> option : options.entrySet()) {
                if (!first) json += ",";
                first = false;
                json += "{\"id\":" + option.getKey() + ",\"name\":\"" + escapeJson(option.getValue()) + "\"}";
            }
            json += "]}";
        }
        json += "]";
        json = json.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
        return "<script id='classification-data' type='application/json'>" + json + "</script>";
    }

    static String classificationLocation(Classification kind, Filters filters, String folder, String notice) {
        String query = filters.query();
        return "/classifications?type=" + kind.key + (query.isEmpty() ? "" : "&" + query)
                + (folder.equals("expired") ? "&folder=expired" : "") + (notice.isEmpty() ? "" : "&notice=" + notice);
    }

    static String classificationNav(Classification selected, Filters filters, String folder) {
        String html = "<section class='classification-navigation'><h2 class='section-label'>分類管理</h2><nav aria-label='分類管理'>";
        for (Classification kind : Classification.values()) {
            html += "<a class='folder-link" + (kind == selected ? " is-selected" : "") + "'"
                    + (kind == selected ? " aria-current='page'" : "") + " href='"
                    + escapeHtml(classificationLocation(kind, filters, folder, "")) + "'>" + kind.label + "</a>";
        }
        return html + "</nav></section>";
    }

    // ★ 編集・削除は独立した管理画面で行い、使用件数と解除される内容を表示します。
    static String renderClassificationManagement(Classification kind, String notice, Filters filters, String folder) throws IOException {
        List<ClassificationItem> items = loadClassificationItems(kind);
        String back = escapeHtml(listLocation(filters, folder));
        String hidden = filters.hiddenInputs() + "<input type='hidden' name='folder' value='"
                + (folder.equals("expired") ? "expired" : "") + "'><input type='hidden' name='type' value='" + kind.key + "'>";
        String impact = kind == Classification.TAG ? "このタグだけをTodoから解除します。"
                : "使用中のTodoの" + kind.label + "は「" + kind.emptyLabel + "」に戻ります。";
        String html = "<!DOCTYPE html><html lang='ja'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1'><title>"
                + kind.label + "の管理 | ToDoリスト</title>"
                + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&amp;family=Noto+Sans+JP:wght@400;500;600&amp;display=swap' rel='stylesheet'><style>"
                + pageStyles() + "</style></head><body><div class='workspace'><aside class='sidebar'><div class='brand'>"
                + uiIcon("list") + "<span>ToDoリスト</span></div><div class='sidebar-body'><nav class='folder-navigation' aria-label='メインナビゲーション'>"
                + "<a class='folder-link' href='" + back + "'>" + uiIcon("list") + "Todo一覧に戻る</a></nav>"
                + classificationNav(kind, filters, folder) + "</div></aside><main class='main-pane'><div class='content'>"
                + "<header class='page-header'><p class='page-context'>分類管理</p><h1>" + kind.label + "の管理</h1>"
                + "<p class='retention-note'>新しい分類はTodoの入力欄から登録できます。名称変更は使用中のTodoにも反映されます。</p></header>";
        String message = switch (notice) {
            case "renamed" -> "名前を変更しました。";
            case "deleted" -> "分類との関連付けを解除して削除しました。Todoは残っています。";
            case "invalid" -> "変更できませんでした。名前の空欄・重複や、対象が存在するかを確認してください。";
            case "error" -> "保存できませんでした。もう一度お試しください。";
            default -> "";
        };
        if (!message.isEmpty()) html += "<p class='management-notice' role='status'>" + message + "</p>";
        html += "<p class='management-impact'>" + impact + " Todo自体は削除しません。使用件数には期限切れフォルダも含みます。</p>"
                + "<ul class='management-list'>";
        for (ClassificationItem item : items) {
            html += "<li class='management-row'><div class='management-item'><form class='rename-classification' method='post' action='/classifications/rename'>"
                    + hidden + "<input type='hidden' name='id' value='" + item.id() + "'><label class='field'><span class='sr-only'>"
                    + escapeHtml(item.name()) + "の名前</span><input name='name' value='" + escapeHtml(item.name())
                    + "' required></label><button class='button button-secondary'>保存</button></form><p class='classification-usage'>使用中："
                    + item.uses() + "件</p></div><form class='delete-classification' method='post' action='/classifications/delete'"
                    + " data-name='" + escapeHtml(item.name()) + "' data-impact='" + escapeHtml(impact)
                    + "' data-uses='" + item.uses() + "' onsubmit='return confirmClassificationDelete(this)'>" + hidden
                    + "<input type='hidden' name='id' value='" + item.id() + "'><button class='button button-secondary' aria-label='"
                    + escapeHtml(item.name()) + "を削除'>削除</button></form></li>";
        }
        if (items.isEmpty()) html += "<li class='empty-state'>登録済みの" + kind.label + "はありません。</li>";
        return html + """
                </ul></div></main></div><script>
                function confirmClassificationDelete(form) {
                    return window.confirm('「' + form.dataset.name + '」を削除します。使用中：' + form.dataset.uses
                        + '件。' + form.dataset.impact + ' Todo自体は削除されません。削除しますか？');
                }
                </script></body></html>
                """;
    }

    // ★ 同じ部品を各分類で使い、候補の検索・キーボード操作・その場での登録に対応します。
    static String classificationScript() {
        return """
                <script>
                (() => {
                    const groups = new Map(JSON.parse(document.getElementById('classification-data').textContent).map(group => [group.key, group]));
                    const widgets = [];
                    const nameKey = value => value.normalize('NFKC').replace(/[\\s\\p{Z}]+/gu, ' ').trim().toLowerCase();
                    const cleanName = value => value.replace(/[\\s\\p{Z}]+/gu, ' ').trim();

                    // 登録した分類は、ほかの選択欄やフィルターからもすぐに使えるようにします。
                    function addToFilters(group, item) {
                        const key = group.key === 'category' ? 'filter' : group.key + 'Filter';
                        const field = document.querySelector('[data-filter-group="' + key + '"] .filter-choices');
                        if (!field) return;
                        if (Array.from(field.querySelectorAll('input')).some(input => input.value === String(item.id))) return;
                        field.querySelectorAll('.filter-help').forEach(note => note.remove());
                        const label = document.createElement('label');
                        label.className = 'filter-choice';
                        const input = document.createElement('input');
                        input.type = 'checkbox'; input.name = key; input.value = item.id;
                        const text = document.createElement('span'); text.textContent = item.name;
                        label.append(input, text); field.append(label);
                    }

                    document.querySelectorAll('.classification-combo').forEach((root, index) => {
                        const group = groups.get(root.dataset.kind);
                        const multiple = group.key === 'tag';
                        const input = root.querySelector('.combo-input');
                        const popup = root.querySelector('.combo-popup');
                        const list = root.querySelector('.combo-list');
                        const empty = root.querySelector('.combo-empty');
                        const notice = root.querySelector('.combo-notice');
                        const chips = root.querySelector('.combo-chips');
                        const values = root.querySelector('.combo-values');
                        list.id = 'classification-list-' + index;
                        input.setAttribute('aria-controls', list.id);
                        const state = {root, open: false, creating: false, query: '', active: -1, choices: [],
                            selected: root.dataset.selected.split(',').filter(Boolean).map(Number)};
                        widgets.push(state);
                        const selectedName = () => group.items.find(item => item.id === state.selected[0])?.name || '';

                        function renderSelection() {
                            values.replaceChildren(); chips.replaceChildren();
                            const ids = multiple ? state.selected : [state.selected[0] || 0];
                            ids.forEach(id => {
                                const hidden = document.createElement('input');
                                hidden.type = 'hidden'; hidden.name = group.formName; hidden.value = id;
                                values.append(hidden);
                            });
                            if (multiple) state.selected.forEach(id => {
                                const item = group.items.find(item => item.id === id);
                                if (!item) return;
                                const chip = document.createElement('span'); chip.className = 'combo-chip';
                                const name = document.createElement('span'); name.textContent = item.name;
                                const remove = document.createElement('button'); remove.type = 'button'; remove.textContent = '×';
                                remove.setAttribute('aria-label', item.name + 'を解除');
                                remove.addEventListener('click', () => {
                                    state.selected = state.selected.filter(value => value !== id);
                                    renderSelection(); input.focus({preventScroll: true});
                                    if (state.open) renderOptions();
                                });
                                chip.append(name, remove); chips.append(chip);
                            });
                            if (!state.open) input.value = multiple ? '' : selectedName();
                            input.title = multiple ? '' : selectedName();
                            // ★ クイック表示にも、確定した選択だけを知らせます。
                            root.dispatchEvent(new CustomEvent('classificationchange', {bubbles: true,
                                detail: {kind: group.key, selected: state.selected.map(id => group.items.find(item => item.id === id)).filter(Boolean)}}));
                        }

                        function highlight(next, scroll) {
                            state.active = next;
                            Array.from(list.children).forEach((option, i) => option.classList.toggle('is-active', i === next));
                            const option = list.children[next];
                            if (option) {
                                input.setAttribute('aria-activedescendant', option.id);
                                if (scroll) option.scrollIntoView({block: 'nearest'});
                            } else input.removeAttribute('aria-activedescendant');
                        }

                        function close() {
                            state.open = false; state.query = ''; state.active = -1;
                            popup.hidden = true; root.classList.remove('is-open');
                            input.setAttribute('aria-expanded', 'false'); input.removeAttribute('aria-activedescendant');
                            input.value = multiple ? '' : selectedName();
                        }
                        state.close = close;
                        root.addEventListener('classification-close', close);

                        function renderOptions() {
                            const query = nameKey(state.query);
                            const items = group.items.filter(item => nameKey(item.name).includes(query))
                                .sort((a, b) => a.name.localeCompare(b.name, 'ja', {numeric: true}));
                            state.choices = items.map(item => ({item}));
                            if (!multiple && !query) state.choices.unshift({clear: true});
                            if (query && !group.items.some(item => nameKey(item.name) === query)) state.choices.push({create: cleanName(state.query)});
                            list.replaceChildren();
                            state.choices.forEach((choice, i) => {
                                const option = document.createElement('div');
                                option.className = 'combo-option'; option.id = list.id + '-option-' + i;
                                option.setAttribute('role', 'option');
                                option.setAttribute('aria-selected', String(!!choice.item && state.selected.includes(choice.item.id)));
                                option.textContent = choice.clear ? group.emptyLabel : choice.create
                                    ? '＋「' + choice.create + '」を新規登録' : choice.item.name;
                                if (choice.create) option.classList.add('combo-create');
                                option.addEventListener('pointerdown', event => event.preventDefault());
                                option.addEventListener('pointermove', () => highlight(i, false));
                                option.addEventListener('click', () => choose(choice));
                                list.append(option);
                            });
                            empty.hidden = !!query || !!items.length;
                            empty.textContent = '登録済みの' + group.label + 'はありません。入力して新規登録できます。';
                            let active = state.active;
                            if (active < 0 && !query && !multiple) {
                                active = state.choices.findIndex(choice => choice.item?.id === state.selected[0]);
                            }
                            highlight(Math.min(Math.max(active, 0), state.choices.length - 1), false);
                        }
                        state.renderOptions = renderOptions;

                        function open() {
                            widgets.forEach(widget => { if (widget !== state) widget.close(); });
                            state.open = true; state.query = ''; state.active = -1;
                            popup.hidden = false; root.classList.add('is-open'); input.setAttribute('aria-expanded', 'true');
                            renderOptions();
                        }

                        function selectItem(item, forceAdd) {
                            if (multiple) {
                                if (state.selected.includes(item.id)) {
                                    if (!forceAdd) state.selected = state.selected.filter(id => id !== item.id);
                                } else state.selected.push(item.id);
                                state.query = ''; input.value = ''; renderSelection();
                                if (state.open) renderOptions();
                            } else {
                                state.selected = [item.id]; close(); renderSelection();
                            }
                        }

                        async function choose(choice) {
                            if (!choice || state.creating) return;
                            notice.textContent = ''; notice.classList.remove('is-error');
                            if (choice.clear) { state.selected = []; close(); renderSelection(); return; }
                            if (choice.item) { selectItem(choice.item, false); return; }
                            state.creating = true; input.readOnly = true; root.setAttribute('aria-busy', 'true');
                            notice.textContent = '登録しています…';
                            try {
                                const response = await fetch('/api/classifications/create', {method: 'POST',
                                    headers: {'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'},
                                    body: new URLSearchParams({type: group.key, name: choice.create})});
                                const item = await response.json();
                                if (!response.ok) throw new Error(item.error || '登録できませんでした。');
                                if (!group.items.some(value => value.id === item.id)) group.items.push(item);
                                addToFilters(group, item);
                                selectItem(item, true); notice.textContent = '';
                            } catch (error) {
                                notice.textContent = error.message || '登録できませんでした。もう一度お試しください。';
                                notice.classList.add('is-error');
                            } finally {
                                state.creating = false; input.readOnly = false; root.removeAttribute('aria-busy');
                            }
                        }

                        input.addEventListener('focus', () => { if (!state.open) { open(); input.select(); } });
                        input.addEventListener('click', () => { if (!state.open) { open(); input.select(); } });
                        input.addEventListener('input', () => {
                            if (!state.open) open();
                            state.query = input.value; state.active = 0; renderOptions();
                        });
                        input.addEventListener('keydown', event => {
                            if (event.isComposing || event.keyCode === 229) return;
                            if (event.key === 'Escape' && state.open) { event.preventDefault(); event.stopPropagation(); close(); }
                            else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                                event.preventDefault();
                                if (!state.open) open();
                                const count = state.choices.length;
                                if (count) highlight((state.active + (event.key === 'ArrowDown' ? 1 : -1) + count) % count, true);
                            } else if (event.key === 'Enter') {
                                event.preventDefault();
                                if (!state.open) open(); else choose(state.choices[state.active]);
                            } else if (event.key === 'Tab') close();
                        });
                        root.addEventListener('focusout', () => {
                            setTimeout(() => { if (!root.contains(document.activeElement)) close(); }, 0);
                        });
                        renderSelection();
                    });

                    document.addEventListener('pointerdown', event => {
                        widgets.forEach(widget => { if (!widget.root.contains(event.target)) widget.close(); });
                    });
                    // 分類入力中のEnterや、登録処理が終わる前のTodo送信を防ぎます。
                    document.addEventListener('submit', event => {
                        const pending = widgets.find(widget => event.target.contains(widget.root) && widget.creating);
                        const typing = document.activeElement?.classList.contains('combo-input');
                        if (pending || typing) {
                            event.preventDefault();
                            if (pending) pending.root.querySelector('.combo-notice').textContent = '分類の登録が終わるまでお待ちください。';
                        }
                    });
                })();
                </script>
                """;
    }

    // ★ クイック登録と詳細設定で、同じ入力欄・同じ保存値を共有します。
    static String quickAddScript() {
        return """
                <script>
                (() => {
                    const form = document.getElementById('todo-add-form');
                    if (!form) return;
                    const details = document.getElementById('todo-add-details');
                    const toggle = document.getElementById('todo-details-toggle');
                    const categoryField = form.querySelector('[data-add-setting="category"]');
                    const categorySlot = form.querySelector('[data-editor-slot="category"]');
                    const priority = form.querySelector('select[name="priority"]');
                    const categoryButton = document.getElementById('quick-category-button');
                    const priorityButton = document.getElementById('quick-priority-button');
                    const categoryEditor = document.getElementById('quick-category-editor');
                    const priorityEditor = document.getElementById('quick-priority-editor');
                    const configured = form.querySelector('.configured-settings');
                    const groups = new Map(JSON.parse(document.getElementById('classification-data').textContent).map(group => [group.key, group]));
                    const selections = new Map();
                    let quickKey = null;

                    // 最初の選択も、保存に使う実際の入力値から読み取ります。
                    form.querySelectorAll('.classification-combo').forEach(root => {
                        const group = groups.get(root.dataset.kind);
                        const ids = Array.from(root.querySelectorAll('.combo-values input')).map(input => Number(input.value));
                        selections.set(group.key, group.items.filter(item => ids.includes(item.id)));
                    });

                    function closeQuick(restoreFocus) {
                        const previous = quickKey;
                        quickKey = null;
                        categoryField.querySelector('.classification-combo').dispatchEvent(new Event('classification-close'));
                        if (categoryField.parentElement !== categorySlot) categorySlot.append(categoryField);
                        categoryEditor.hidden = true; priorityEditor.hidden = true;
                        categoryButton.setAttribute('aria-expanded', 'false');
                        priorityButton.setAttribute('aria-expanded', 'false');
                        form.querySelectorAll('.quick-setting').forEach(root => root.classList.remove('is-open'));
                        if (restoreFocus && previous) (previous === 'category' ? categoryButton : priorityButton).focus({preventScroll: true});
                    }

                    function updateViews() {
                        const categoryName = selections.get('category')?.[0]?.name || '未分類';
                        categoryButton.querySelector('.chip-label').textContent = 'カテゴリ：' + categoryName;
                        categoryButton.title = 'カテゴリ：' + categoryName;
                        priorityButton.querySelector('.chip-label').textContent = '重要度：' + priority.value;
                        priorityEditor.querySelectorAll('[data-priority]').forEach(option => {
                            const selected = option.dataset.priority === priority.value;
                            option.setAttribute('aria-selected', String(selected)); option.tabIndex = selected ? 0 : -1;
                        });
                        configured.replaceChildren();
                        for (const key of ['location', 'person', 'project', 'tag']) {
                            for (const item of selections.get(key) || []) {
                                const button = document.createElement('button');
                                button.type = 'button'; button.className = 'setting-chip configured-chip';
                                const label = groups.get(key).label + '：' + item.name;
                                button.title = label; button.setAttribute('aria-label', label + 'を編集');
                                const text = document.createElement('span'); text.className = 'chip-label'; text.textContent = label;
                                button.append(text);
                                button.addEventListener('click', () => {
                                    setDetails(true);
                                    form.querySelector('[data-kind="' + key + '"] .combo-input').focus();
                                });
                                configured.append(button);
                            }
                        }
                        configured.hidden = !details.hidden || !configured.childElementCount;
                    }

                    function setDetails(expanded) {
                        closeQuick(false);
                        if (!expanded) {
                            form.querySelectorAll('.classification-combo').forEach(root => root.dispatchEvent(new Event('classification-close')));
                            if (details.contains(document.activeElement)) toggle.focus({preventScroll: true});
                        }
                        details.hidden = !expanded;
                        toggle.setAttribute('aria-expanded', String(expanded));
                        details.classList.toggle('is-expanding', expanded);
                        updateViews();
                    }
                    details.addEventListener('animationend', () => details.classList.remove('is-expanding'));
                    toggle.addEventListener('click', () => setDetails(details.hidden));

                    categoryButton.addEventListener('click', () => {
                        if (quickKey === 'category') { closeQuick(true); return; }
                        closeQuick(false);
                        if (!details.hidden) { categoryField.querySelector('.combo-input').focus(); return; }
                        // 同じ入力欄を移動させるため、選択状態や作成途中の通信が二重になりません。
                        categoryEditor.append(categoryField);
                        categoryEditor.hidden = false; quickKey = 'category';
                        categoryButton.setAttribute('aria-expanded', 'true');
                        categoryButton.closest('.quick-setting').classList.add('is-open');
                        categoryField.querySelector('.combo-input').focus();
                    });
                    priorityButton.addEventListener('click', () => {
                        if (quickKey === 'priority') { closeQuick(true); return; }
                        closeQuick(false); quickKey = 'priority'; priorityEditor.hidden = false;
                        priorityButton.setAttribute('aria-expanded', 'true');
                        priorityButton.closest('.quick-setting').classList.add('is-open');
                        priorityEditor.querySelector('[data-priority="' + priority.value + '"]').focus();
                    });
                    priorityEditor.querySelectorAll('[data-priority]').forEach(option => {
                        option.addEventListener('click', () => {
                            priority.value = option.dataset.priority;
                            priority.dispatchEvent(new Event('change', {bubbles: true}));
                            closeQuick(true);
                        });
                    });
                    priorityEditor.addEventListener('keydown', event => {
                        if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
                        event.preventDefault();
                        const options = Array.from(priorityEditor.querySelectorAll('[data-priority]'));
                        const index = options.indexOf(document.activeElement);
                        const next = event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1
                            : (index + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length;
                        options.forEach((option, i) => option.tabIndex = i === next ? 0 : -1);
                        options[next].focus();
                    });
                    priority.addEventListener('change', updateViews);
                    form.addEventListener('classificationchange', event => {
                        selections.set(event.detail.kind, event.detail.selected);
                        updateViews();
                        if (event.detail.kind === 'category' && quickKey === 'category') closeQuick(true);
                    });
                    document.addEventListener('pointerdown', event => {
                        if (quickKey && !form.querySelector('[data-quick-setting="' + quickKey + '"]').contains(event.target)) closeQuick(false);
                    });
                    form.addEventListener('focusout', () => {
                        setTimeout(() => {
                            if (quickKey && !form.querySelector('[data-quick-setting="' + quickKey + '"]').contains(document.activeElement)) closeQuick(false);
                        }, 0);
                    });
                    form.addEventListener('keydown', event => {
                        if (event.key === 'Escape' && quickKey) {
                            event.preventDefault(); event.stopPropagation(); closeQuick(true);
                        }
                    });
                    updateViews();
                })();
                </script>
                """;
    }

    // ★ 画像の配置に合わせ、ソートとフィルターを一つのポップアップにまとめます。
    static String organizeDialog(String filterForm) {
        String html = """
                <dialog id='organize-dialog' class='organize-overlay' aria-labelledby='organize-title'>
                 <div class='organize-dialog'>
                  <h2 id='organize-title' class='sr-only'>並び替えとフィルター</h2>
                  <div class='organize-tabs' role='tablist' aria-label='一覧の表示設定'>
                    <button type='button' id='sort-tab' role='tab' aria-selected='true' aria-controls='sort-panel' onclick="showOrganizeTab('sort')">ソート</button>
                    <button type='button' id='filter-tab' role='tab' aria-selected='false' aria-controls='filter-panel' tabindex='-1' onclick="showOrganizeTab('filter')">フィルター</button>
                  </div>
                  <div id='sort-panel' class='organize-panel' role='tabpanel' aria-labelledby='sort-tab'>
                    <fieldset class='sort-fieldset'><legend>並び替え</legend><div class='direction-options'>
                """;
        html += sortChoice("sortDirection", "desc", "降順", false)
                + sortChoice("sortDirection", "asc", "昇順", true);
        html += "</div></fieldset><fieldset class='sort-fieldset'><legend>基準</legend><div class='criteria-options'>";
        String[][] criteria = {
                { "added", "追加" }, { "deadline", "期限" }, { "category", "カテゴリ" },
                { "priority", "重要度" }
        };
        for (String[] criterion : criteria) {
            html += sortChoice("sortKey", criterion[0], criterion[1], criterion[0].equals("deadline"));
        }
        html += """
                    </div></fieldset><p id='sort-help' class='sort-help' aria-live='polite'></p>
                  </div>
                  <div id='filter-panel' class='organize-panel' role='tabpanel' aria-labelledby='filter-tab' hidden>
                """ + filterForm + """
                  </div>
                  <footer class='organize-footer'>
                    <button type='button' class='button button-secondary' onclick="document.getElementById('organize-dialog').close()">キャンセル</button>
                    <button type='button' class='button button-primary' onclick='applyOrganize()'>決定</button>
                  </footer>
                 </div>
                </dialog>
                """;
        return html;
    }

    // ★ ラジオボタン（一つだけ選ぶ欄）を、押しやすいボタンの見た目にします。
    static String sortChoice(String name, String value, String label, boolean checked) {
        return "<label class='sort-choice'><input type='radio' name='" + name + "' value='" + value + "'"
                + (checked ? " checked" : "") + "><span>" + label + "</span></label>";
    }

    // ★ 決定したときだけ並びを反映し、キャンセルでは現在の一覧を変えません。
    static String organizeScript() {
        return """
                <script>
                const organizeDialog = document.getElementById('organize-dialog');
                const sortLabels = {added: '追加', deadline: '期限', category: 'カテゴリ', priority: '重要度'};
                // ★ 並び替えの基準と昇順・降順を、同じブラウザに保存して読み戻します。
                const sortStorageKey = 'todo.sortPreference';
                let appliedSort = {key: 'deadline', direction: 'asc'};
                try {
                    const saved = JSON.parse(localStorage.getItem(sortStorageKey));
                    if (saved && Object.hasOwn(sortLabels, saved.key) && ['asc', 'desc'].includes(saved.direction)) {
                        appliedSort = {key: saved.key, direction: saved.direction};
                    }
                } catch (error) {
                    // ブラウザの保存が使えない場合も、一覧の表示は続けます。
                }
                let organizeTab = 'sort';

                function openOrganize(tab) {
                    // 前回キャンセルした選択は捨て、現在の設定から選び直します。
                    document.getElementById('list-filter-form').reset();
                    updateFilterSummaries();
                    organizeDialog.querySelectorAll('[name="sortKey"]').forEach(input => {
                        input.checked = input.value === appliedSort.key;
                    });
                    organizeDialog.querySelectorAll('[name="sortDirection"]').forEach(input => {
                        input.checked = input.value === appliedSort.direction;
                    });
                    showOrganizeTab(tab);
                    organizeDialog.showModal();
                    document.getElementById(tab + '-tab').focus();
                }

                function showOrganizeTab(tab) {
                    organizeTab = tab;
                    for (const name of ['sort', 'filter']) {
                        const selected = name === tab;
                        const button = document.getElementById(name + '-tab');
                        button.setAttribute('aria-selected', String(selected));
                        button.tabIndex = selected ? 0 : -1;
                        document.getElementById(name + '-panel').hidden = !selected;
                    }
                    updateSortHelp();
                }

                function updateSortHelp() {
                    const key = organizeDialog.querySelector('[name="sortKey"]:checked').value;
                    const direction = organizeDialog.querySelector('[name="sortDirection"]:checked').value;
                    let text;
                    if (key === 'added') {
                        text = direction === 'asc' ? '古い追加順に並びます。' : '新しい追加順に並びます。';
                    } else if (key === 'deadline') {
                        text = direction === 'asc' ? '期限が近い順に並びます。' : '期限が遠い順に並びます。';
                    } else if (key === 'priority') {
                        text = direction === 'asc' ? '低 → 中 → 高の順に並びます。' : '高 → 中 → 低の順に並びます。';
                    } else {
                        text = direction === 'asc' ? '名前の昇順に並びます。' : '名前の降順に並びます。';
                    }
                    document.getElementById('sort-help').textContent = text + ' 未設定の項目は末尾になります。';
                }

                function applyOrganize() {
                    if (organizeTab === 'filter') {
                        document.getElementById('list-filter-form').requestSubmit();
                        return;
                    }
                    const key = organizeDialog.querySelector('[name="sortKey"]:checked').value;
                    const direction = organizeDialog.querySelector('[name="sortDirection"]:checked').value;
                    sortTodoRows(key, direction);
                    try {
                        localStorage.setItem(sortStorageKey, JSON.stringify(appliedSort));
                    } catch (error) {
                        // 保存が制限されていても、その画面での並び替えは反映します。
                    }
                    organizeDialog.close();
                }

                // ★ 決定時と画面の読み込み時に、同じ処理で一覧を並び替えます。
                function sortTodoRows(key, direction) {
                    const rows = Array.from(document.querySelectorAll('#todo-list > li[data-added]'));
                    const collator = new Intl.Collator('ja', {numeric: true, sensitivity: 'base'});
                    const priorities = {'低': 1, '中': 2, '高': 3};
                    rows.sort((a, b) => {
                        const x = a.dataset[key];
                        const y = b.dataset[key];
                        // 未設定は、昇順・降順のどちらでも末尾に置きます。
                        if (!x && y) return 1;
                        if (x && !y) return -1;
                        let result = 0;
                        if (x && y) {
                            if (key === 'added') result = Number(x) - Number(y);
                            else if (key === 'priority') result = priorities[x] - priorities[y];
                            else result = collator.compare(x, y);
                        }
                        // 同じ値なら追加順にして、操作のたびに並びが揺れないようにします。
                        return (direction === 'desc' ? -result : result) || Number(a.dataset.added) - Number(b.dataset.added);
                    });
                    const list = document.getElementById('todo-list');
                    rows.forEach(row => list.appendChild(row));
                    appliedSort = {key, direction};
                    document.getElementById('current-sort').textContent = sortLabels[key] + '・' + (direction === 'asc' ? '昇順' : '降順');
                }

                function updateFilterSummaries() {
                    document.querySelectorAll('#list-filter-form [data-filter-group]').forEach(group => {
                        const selected = Array.from(group.querySelectorAll('input[type="checkbox"]:checked'))
                            .filter(input => input.value !== '');
                        const names = selected.map(input => input.parentElement.querySelector('span').textContent);
                        const summary = group.querySelector('.filter-summary');
                        summary.textContent = names.length > 1 ? names.length + '件選択' : names[0] || 'すべて';
                        summary.title = names.length ? names.join('、') : 'すべて';
                    });
                }

                // 「すべて」と個別条件を同時に選ばず、最後の条件を外したら「すべて」に戻します。
                document.getElementById('list-filter-form').addEventListener('change', event => {
                    const input = event.target;
                    if (!input.matches('input[type="checkbox"]')) return;
                    const group = input.closest('[data-filter-group]');
                    if (!group) return;
                    const choices = Array.from(group.querySelectorAll('input[type="checkbox"]'));
                    const all = choices.find(choice => choice.value === '');
                    const specific = choices.filter(choice => choice.value !== '');
                    if (input === all && all.checked) specific.forEach(choice => choice.checked = false);
                    all.checked = !specific.some(choice => choice.checked);
                    updateFilterSummaries();
                });
                organizeDialog.addEventListener('change', event => {
                    if (['sortKey', 'sortDirection'].includes(event.target.name)) updateSortHelp();
                });
                // 左右キーでも、画像上部のタブを切り替えられます。
                organizeDialog.querySelector('[role="tablist"]').addEventListener('keydown', event => {
                    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
                    event.preventDefault();
                    const tab = event.key === 'Home' ? 'sort' : event.key === 'End' ? 'filter' : organizeTab === 'sort' ? 'filter' : 'sort';
                    showOrganizeTab(tab);
                    document.getElementById(tab + '-tab').focus();
                });
                // ★ 更新・追加・編集などで画面を開き直しても、保存した順序を反映します。
                sortTodoRows(appliedSort.key, appliedSort.direction);
                </script>
                """;
    }

    // ★ 色・幅・余白・文字組みを指定のデザイン方針に揃えます。
    static String pageStyles() {
        return """
                :root {
                    --background: #F6F7F5;
                    --main: #FFFFFF;
                    --sidebar: #EAEEE9;
                    --text: #242D27;
                    --muted: #8A908B;
                    --accent: #7D9B83;
                    --selected: #D4DED2;
                    --line: #EFF1EF;
                }
                * { box-sizing: border-box; }
                body { margin: 0; background: var(--background); color: var(--text); font-family: 'Inter', 'Noto Sans JP', sans-serif; font-size: 15px; line-height: 1.65; -webkit-font-smoothing: antialiased; }
                h1, h2, p, ul { margin: 0; }
                button, input, select { font: inherit; color: inherit; }
                button, a, input, select { transition: background-color 180ms ease, border-color 180ms ease, color 180ms ease, opacity 180ms ease; }
                a { color: inherit; text-decoration: none; }
                button { cursor: pointer; }
                button:disabled, input:disabled, select:disabled { opacity: .45; cursor: not-allowed; }
                :is(button, a, input, select):focus-visible { outline: 3px solid #7D9B8340; outline-offset: 3px; }
                .workspace { display: grid; grid-template-columns: 232px minmax(0, 1fr); min-height: 100vh; }
                .sidebar { position: sticky; top: 0; height: 100vh; overflow-y: auto; padding: 36px 22px; background: var(--sidebar); border-right: 1px solid #E2E7E1; }
                .brand { display: flex; align-items: center; gap: 10px; font-size: 15px; font-weight: 600; letter-spacing: -.02em; }
                .brand .icon { width: 23px; height: 23px; color: #617C66; }
                .sidebar-body { margin-top: 58px; }
                .folder-navigation { display: flex; flex-direction: column; gap: 5px; margin-bottom: 30px; }
                .folder-link { display: flex; align-items: center; gap: 9px; padding: 9px 10px; border-radius: 9px; color: #66726A; font-size: 13px; }
                .folder-link:hover { background: #DDE5DA; color: var(--text); }
                .folder-link.is-selected { background: var(--selected); color: var(--text); }
                .section-label { display: flex; align-items: center; gap: 7px; margin-bottom: 16px; color: #66726A; font-size: 12px; font-weight: 500; }
                .filter-form, .genre-form { display: flex; flex-direction: column; gap: 10px; }
                .filter-form select { background-color: var(--selected); border-color: transparent; }
                .show-all { align-self: center; margin-top: 2px; padding: 3px 6px; color: #6D786F; font-size: 12px; }
                .show-all:hover { color: var(--text); text-decoration: underline; text-underline-offset: 4px; }
                .genre-registration { margin-top: 36px; padding-top: 26px; border-top: 1px solid #DCE3DA; }
                .genre-form .field-label { margin-bottom: -4px; }
                .detail-registration { margin-top: 22px; padding-top: 18px; border-top: 1px solid #DCE3DA; }
                .detail-registration summary { cursor: pointer; color: #66726A; font-size: 12px; }
                .detail-registration[open] summary { margin-bottom: 16px; }
                .detail-registration summary:focus-visible { outline: 3px solid #7D9B8340; outline-offset: 3px; }
                .sidebar input, .sidebar select { width: 100%; font-size: 13px; }
                .sidebar .button { width: 100%; min-height: 38px; font-size: 12px; }
                .classification-navigation { margin-top: 32px; padding-top: 24px; border-top: 1px solid #DCE3DA; }
                .classification-navigation nav { display: flex; flex-direction: column; gap: 4px; }
                .main-pane { min-width: 0; padding: 64px 48px 96px; background: var(--main); }
                .content { max-width: 860px; margin: 0 auto; }
                .page-header { margin-bottom: 42px; }
                .page-context { margin-bottom: 8px; color: var(--muted); font-size: 12px; overflow-wrap: anywhere; }
                .retention-note { margin-top: 16px; color: #718076; font-size: 13px; }
                .task-note { margin-top: 3px; color: var(--muted); font-size: 11px; }
                .archived-value { color: #718076; font-size: 12px; overflow-wrap: anywhere; }
                h1 { font-size: 30px; font-weight: 600; letter-spacing: -.035em; line-height: 1.4; }
                .small-heading { margin-bottom: 14px; font-size: 14px; font-weight: 500; }
                .add-section { margin-bottom: 48px; }
                .add-form { display: flex; flex-direction: column; align-items: stretch; gap: 14px; }
                .add-form > .field:first-child input { min-height: 48px; font-size: 16px; }
                .quick-add-controls { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
                .quick-settings { display: flex; flex: 1; flex-wrap: wrap; align-items: center; gap: 8px; min-width: 0; }
                .quick-setting { position: relative; max-width: 100%; }
                .quick-setting.is-open { z-index: 80; }
                .setting-chip { display: inline-flex; align-items: center; gap: 6px; max-width: min(280px, 100%); min-height: 34px; padding: 6px 10px; border: 1px solid #E2E7E1; border-radius: 999px; background: var(--background); color: #526658; font-size: 12px; line-height: 1.5; text-align: left; }
                .setting-chip:hover { background: var(--selected); border-color: #C1D0BE; }
                .chip-label { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
                .details-toggle { display: inline-flex; align-items: center; gap: 6px; min-height: 34px; padding: 6px 10px; border: 1px solid transparent; border-radius: 8px; background: transparent; color: #65746A; font-size: 12px; }
                .details-toggle:hover, .details-toggle[aria-expanded='true'] { background: var(--sidebar); }
                .quick-add-controls .add-submit { flex: 0 0 auto; min-height: 38px; padding: 7px 15px; font-size: 13px; }
                .quick-editor { position: absolute; top: calc(100% + 8px); left: 0; z-index: 80; width: min(320px, calc(100vw - 64px)); padding: 12px; border: 1px solid #DDE3DC; border-radius: 10px; background: var(--main); box-shadow: 0 4px 14px #242D2710; }
                .quick-editor[hidden], .configured-settings[hidden], .todo-add-details[hidden] { display: none; }
                .priority-editor { width: 140px; padding: 4px; }
                .priority-editor [role='option'] { display: block; width: 100%; min-height: 34px; padding: 6px 10px; border: 0; border-radius: 6px; background: transparent; text-align: left; font-size: 14px; }
                .priority-editor [role='option']:hover { background: var(--background); }
                .priority-editor [aria-selected='true'] { background: var(--selected); }
                .configured-settings { display: flex; flex-wrap: wrap; gap: 8px; }
                .configured-chip { max-width: min(240px, 100%); background: var(--sidebar); border-color: transparent; }
                .todo-add-details { margin-top: 6px; padding-top: 20px; border-top: 1px solid var(--line); overflow: visible; }
                .add-details-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px 20px; align-items: start; }
                .detail-slot { min-width: 0; }
                .todo-add-details.is-expanding { animation: quick-details-reveal 180ms ease-out; }
                @keyframes quick-details-reveal { from { opacity: .35; } to { opacity: 1; } }
                .field { display: flex; min-width: 0; flex-direction: column; gap: 7px; }
                .field-label { color: #718076; font-size: 12px; font-weight: 400; }
                input, select { min-width: 0; min-height: 42px; padding: 8px 11px; border: 1px solid #DDE3DC; border-radius: 9px; background: var(--main); outline: none; }
                input::placeholder { color: var(--muted); }
                input:hover, select:hover { border-color: #C6D1C5; }
                input:focus, select:focus { border-color: var(--accent); }
                .button { display: inline-flex; align-items: center; justify-content: center; gap: 7px; min-height: 42px; padding: 8px 17px; border: 1px solid transparent; border-radius: 9px; font-size: 14px; font-weight: 500; }
                .button-primary { background: var(--accent); color: #17251B; }
                .button-primary:hover { background: #91AA96; }
                .button-secondary { background: #FFFFFF66; border-color: #D2DBD0; }
                .button-secondary:hover { background: #FFFFFFA6; border-color: #BACAB8; }
                .icon { width: 16px; height: 16px; flex-shrink: 0; }
                .list-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 24px; }
                .list-toolbar h2 { font-size: 18px; font-weight: 500; letter-spacing: -.02em; }
                .list-order { display: flex; align-items: center; gap: 12px; }
                .current-sort { color: #718076; font-size: 12px; }
                .sort-trigger { min-height: 36px; padding: 6px 12px; font-size: 12px; background: var(--main); }
                /* 開閉に合わせて本体を伸ばし、画面を超えた分だけ外側全体で移動します。 */
                .organize-overlay { position: fixed; inset: 0; width: 100%; height: 100%; max-width: none; max-height: none; margin: 0; padding: 24px 16px; overflow-y: auto; border: 0; background: transparent; color: var(--text); }
                .organize-overlay[open] { display: flex; flex-direction: column; }
                .organize-overlay::backdrop { background: #242D2740; }
                .organize-dialog { flex: 0 0 auto; width: min(520px, 100%); height: auto; max-height: none; margin: auto; padding: 28px; overflow: visible; border: 1px solid #E2E7E1; border-radius: 16px; background: var(--main); color: var(--text); }
                .organize-tabs { display: grid; grid-template-columns: 1fr 1fr; gap: 4px; padding: 4px; margin-bottom: 26px; background: var(--sidebar); border-radius: 12px; }
                .organize-tabs button { min-height: 42px; padding: 8px; border: 0; border-radius: 9px; background: transparent; font-size: 16px; font-weight: 500; }
                .organize-tabs button:hover { background: #DDE5DA; }
                .organize-tabs button[aria-selected='true'] { background: var(--selected); }
                .organize-panel { min-height: 300px; }
                .organize-panel[hidden] { display: none; }
                .sort-fieldset { min-width: 0; margin: 0 0 24px; padding: 0; border: 0; }
                .sort-fieldset legend { margin-bottom: 12px; padding: 0; font-size: 18px; font-weight: 500; }
                .direction-options, .criteria-options { display: grid; gap: 12px; }
                .direction-options { grid-template-columns: 1fr 1fr; }
                .criteria-options { grid-template-columns: repeat(2, minmax(0, 1fr)); }
                .sort-choice { position: relative; min-width: 0; cursor: pointer; }
                .sort-choice input { position: absolute; width: 1px; height: 1px; min-height: 0; margin: 0; padding: 0; opacity: 0; }
                .sort-choice span { display: flex; align-items: center; justify-content: center; min-height: 40px; padding: 6px 4px; border: 1px solid #E2E7E1; border-radius: 10px; font-size: 14px; transition: background-color 180ms ease, border-color 180ms ease; }
                .sort-choice:hover span { background: var(--background); border-color: #C6D1C5; }
                .sort-choice input:checked + span { background: var(--selected); border-color: #C1D0BE; }
                .sort-choice input:focus-visible + span { outline: 3px solid #7D9B8340; outline-offset: 3px; }
                .sort-help { color: #718076; font-size: 12px; line-height: 1.8; }
                .dialog-filter-form { display: flex; flex-direction: column; gap: 20px; }
                .dialog-filter-form .show-all { align-self: flex-start; margin: 0; }
                .filter-accordion, .advanced-filters { min-width: 0; }
                .filter-accordion > summary, .advanced-filters > summary { display: flex; align-items: center; gap: 10px; min-height: 42px; padding: 8px 12px; border-radius: 8px; list-style: none; cursor: pointer; }
                .filter-accordion > summary { background: var(--sidebar); font-size: 16px; }
                .filter-accordion > summary:hover { background: #DDE5DA; }
                .filter-accordion > summary::-webkit-details-marker, .advanced-filters > summary::-webkit-details-marker { display: none; }
                .filter-accordion > summary:focus-visible, .advanced-filters > summary:focus-visible { outline: 3px solid #7D9B8340; outline-offset: 3px; }
                .filter-summary { min-width: 0; max-width: 52%; margin-left: auto; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #607166; font-size: 13px; }
                .accordion-arrow { flex: 0 0 8px; width: 8px; height: 8px; margin: -4px 3px 0 2px; border-right: 1.5px solid currentColor; border-bottom: 1.5px solid currentColor; transform: rotate(45deg); }
                .filter-accordion[open] > summary .accordion-arrow, .advanced-filters[open] > summary .accordion-arrow { margin-top: 4px; transform: rotate(225deg); }
                .advanced-filters > summary { justify-content: space-between; margin-bottom: 2px; padding-inline: 10px; font-size: 18px; }
                .advanced-filter-content { display: flex; flex-direction: column; gap: 20px; padding-top: 10px; padding-left: 10px; }
                .filter-choices { display: flex; flex-direction: column; gap: 6px; min-width: 0; margin: 0; padding: 10px 10px 0; border: 0; }
                .filter-choice { display: flex; align-items: center; gap: 10px; min-height: 36px; padding: 5px 8px; border-radius: 7px; font-size: 14px; cursor: pointer; }
                .filter-choice:hover { background: var(--background); }
                .filter-choice:has(input:checked) { background: #EAEEE980; }
                .filter-choice input[type='checkbox'] { flex: 0 0 16px; width: 16px; height: 16px; min-height: 0; margin: 0; padding: 0; accent-color: var(--accent); }
                .filter-choice span { min-width: 0; overflow-wrap: anywhere; }
                .filter-help { color: #718076; font-size: 12px; line-height: 1.8; }
                .organize-footer { display: flex; justify-content: flex-end; gap: 12px; margin-top: 32px; }
                .organize-footer .button { min-width: 112px; }
                .column-headings, .task-row { display: grid; grid-template-columns: minmax(0, 1fr) 84px 100px 190px; gap: 16px; align-items: center; }
                .column-headings { padding: 0 0 11px; color: var(--muted); font-size: 11px; }
                .actions-heading { text-align: right; }
                .todo-list { list-style: none; padding: 0; border-top: 1px solid var(--line); }
                .task-row { min-height: 72px; padding: 16px 0; border-bottom: 1px solid var(--line); }
                .task-row[hidden] { display: none; }
                .task-title-box { min-width: 0; font-size: 14px; overflow-wrap: anywhere; }
                .todo-detail-trigger { display: inline-block; max-width: 100%; padding: 4px 0; border: 0; border-radius: 4px; background: transparent; text-align: left; font-size: inherit; line-height: inherit; overflow-wrap: anywhere; }
                .todo-detail-trigger:hover { color: #526D58; text-decoration: underline; text-underline-offset: 4px; }
                .task-status { font-size: 12px; }
                .unfinished-status { color: #718076; }
                .todo-detail-dialog { position: fixed; inset: 0; width: 100%; height: 100%; max-width: none; max-height: none; margin: 0; padding: 24px 16px; overflow-y: auto; border: 0; background: transparent; color: var(--text); }
                .todo-detail-dialog[open] { display: flex; flex-direction: column; }
                .todo-detail-dialog::backdrop { background: #242D2740; }
                .todo-dialog-content { flex: 0 0 auto; width: min(620px, 100%); margin: auto; padding: 28px; border: 1px solid #E2E7E1; border-radius: 16px; background: var(--main); }
                .todo-dialog-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 28px; }
                .todo-dialog-header h2 { min-width: 0; font-size: 20px; font-weight: 500; overflow-wrap: anywhere; }
                .todo-dialog-close { flex-shrink: 0; min-height: 34px; padding: 5px 10px; font-size: 12px; }
                .todo-dialog-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px 20px; }
                .todo-detail-dialog .task-note { margin-bottom: 20px; color: #718076; font-size: 12px; }
                .todo-dialog-actions { display: flex; justify-content: flex-end; align-items: center; gap: 16px; margin-top: 28px; padding-top: 18px; border-top: 1px solid var(--line); }
                .todo-dialog-actions .delete-link { width: auto; gap: 6px; padding: 5px 9px; font-size: 12px; }
                .completion-mark { color: #617F68; }
                .task-field { display: flex; align-items: center; min-width: 0; gap: 6px; }
                .control-label { flex: 1; min-width: 0; }
                .task-field select, .task-field input, .task-detail select { width: 100%; min-height: 34px; height: 34px; padding: 5px 6px; border-color: #E5EAE3; border-radius: 8px; font-size: 12px; }
                .task-field select:hover, .task-field input:hover, .task-detail select:hover { border-color: #C6D1C5; }
                .task-details { grid-column: 1 / -1; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; padding-top: 4px; }
                .task-detail { display: flex; align-items: flex-end; gap: 6px; min-width: 0; }
                .task-detail .field { flex: 1; }
                .classification-combo { position: relative; width: 100%; min-width: 0; }
                .classification-combo.is-open { z-index: 40; }
                .combo-entry { position: relative; }
                .combo-input { width: 100%; text-overflow: ellipsis; }
                .combo-popup { position: absolute; top: calc(100% + 6px); left: 0; z-index: 50; width: max-content; min-width: max(100%, 210px); max-width: min(360px, calc(100vw - 64px)); padding: 5px; border: 1px solid #DDE3DC; border-radius: 10px; background: var(--main); box-shadow: 0 4px 14px #242D2710; }
                .combo-popup[hidden] { display: none; }
                .combo-list { max-height: 230px; overflow-y: auto; overscroll-behavior: contain; }
                .combo-option { padding: 8px 10px; border-radius: 6px; font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; cursor: pointer; }
                .combo-option[aria-selected='true'] { background: #EAEEE9; font-weight: 500; }
                .combo-option.is-active { background: var(--selected); }
                .combo-create { color: #45614C; }
                .combo-empty, .combo-notice { padding: 4px 5px; color: #718076; font-size: 12px; overflow-wrap: anywhere; }
                .combo-notice:empty { display: none; }
                .combo-notice.is-error { color: #8A493B; }
                .combo-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 7px; }
                .combo-chips:empty { display: none; }
                .combo-chip { display: inline-flex; align-items: center; gap: 4px; max-width: 100%; padding: 2px 4px 2px 8px; border-radius: 8px; background: var(--selected); font-size: 12px; }
                .combo-chip > span { min-width: 0; overflow-wrap: anywhere; }
                .combo-chip button { flex: 0 0 24px; width: 24px; height: 24px; padding: 0; border: 0; border-radius: 5px; background: transparent; color: #516657; }
                .combo-chip button:hover { background: #BECDBB; }
                .task-field .combo-input, .task-detail .combo-input, .task-tags .combo-input { min-height: 34px; height: 34px; padding: 5px 6px; border-color: #E5EAE3; border-radius: 8px; font-size: 12px; }
                .tag-picker { display: flex; flex-direction: column; gap: 7px; min-width: 0; }
                .add-form > .tag-picker { grid-column: 1 / -1; }
                .task-tags > .tag-picker { flex: 1; }
                .management-notice { margin-bottom: 20px; padding: 12px 14px; background: var(--sidebar); border-radius: 9px; font-size: 14px; }
                .management-impact { margin-bottom: 24px; color: #718076; font-size: 13px; }
                .management-list { padding: 0; list-style: none; border-top: 1px solid var(--line); }
                .management-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: start; gap: 16px; padding: 18px 0; border-bottom: 1px solid var(--line); }
                .management-item { min-width: 0; }
                .rename-classification { display: flex; align-items: center; gap: 10px; }
                .rename-classification .field { flex: 1; }
                .rename-classification input { width: 100%; }
                .classification-usage { margin-top: 6px; color: #718076; font-size: 12px; }
                .delete-classification .button { color: #7D6358; }
                .detail-value { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
                .tag-options { min-width: 0; margin: 0; padding: 0; border: 0; }
                .tag-options legend { margin-bottom: 7px; padding: 0; color: #718076; font-size: 12px; }
                .add-form > .tag-options, .task-tags { grid-column: 1 / -1; }
                .task-tags { display: flex; align-items: flex-end; gap: 12px; min-width: 0; }
                form[data-autosave] { display: flex; flex-direction: column; align-items: stretch; gap: 4px; min-width: 0; }
                .autosave-feedback { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; min-height: 16px; color: #718076; font-size: 11px; line-height: 1.4; }
                .autosave-feedback.is-error { color: #8A493B; }
                .autosave-retry { padding: 2px 6px; border: 1px solid #DDE3DC; border-radius: 5px; background: var(--main); font-size: 11px; }
                .autosave-retry[hidden] { display: none; }
                .task-tags > .tag-options { flex: 1; }
                .tag-choices { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; min-width: 0; }
                .tag-choice { display: inline-flex; align-items: center; gap: 6px; max-width: 100%; min-height: 32px; padding: 3px 7px; border: 1px solid var(--line); border-radius: 8px; font-size: 12px; cursor: pointer; }
                .tag-choice:hover { background: var(--background); }
                .tag-choice:has(input:checked) { background: var(--selected); border-color: #C1D0BE; }
                .tag-choice input[type='checkbox'] { flex: 0 0 15px; width: 15px; height: 15px; min-height: 0; margin: 0; padding: 0; accent-color: var(--accent); }
                .tag-choice span, .tag-name { overflow-wrap: anywhere; min-width: 0; }
                .tag-hint { color: #718076; font-size: 12px; }
                .tag-name { padding: 3px 8px; background: var(--selected); border-radius: 8px; font-size: 12px; }
                .task-actions { display: flex; justify-content: flex-end; align-items: center; gap: 8px; }
                .archived-list .column-headings, .archived-list .task-row { grid-template-columns: minmax(0, 1fr) 84px 100px 190px; }
                .restore-form { margin: 0; display: flex; }
                .restore-button { min-height: 32px; padding: 5px 10px; border: 1px solid #DDE3DC; border-radius: 8px; background: var(--main); color: #607D66; font-size: 12px; white-space: nowrap; }
                .restore-button:hover { background: var(--sidebar); border-color: var(--selected); color: var(--text); }
                .complete-link { padding: 5px 2px; color: #607D66; border-radius: 7px; font-size: 12px; white-space: nowrap; }
                .complete-link:hover { color: var(--text); background: var(--sidebar); }
                .delete-link { display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 30px; border-radius: 7px; color: var(--muted); }
                .delete-link:hover { background: #F1EDEA; color: #765F55; }
                .empty-state { padding: 62px 12px; text-align: center; color: var(--muted); font-size: 14px; }
                .sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
                @media (max-width: 1100px) {
                    .main-pane { padding: 48px 28px 72px; }
                    .column-headings, .task-row, .archived-list .column-headings, .archived-list .task-row { gap: 10px; grid-template-columns: minmax(0, 1fr) 72px 96px 174px; }
                }
                @media (max-width: 960px) {
                    .column-headings { display: none; }
                    .task-row { grid-template-columns: repeat(6, minmax(0, 1fr)); row-gap: 12px; }
                    .archived-list .task-row { grid-template-columns: repeat(6, minmax(0, 1fr)); }
                    .task-title-box { grid-column: 1 / span 4; grid-row: 1; }
                    .task-status { grid-column: 5 / span 2; grid-row: 1; text-align: right; }
                    .task-priority { grid-column: 1 / span 2; grid-row: 2; }
                    .task-deadline { grid-column: 3 / span 4; grid-row: 2; }
                }
                @media (max-width: 700px) {
                    .workspace { display: block; }
                    .sidebar { position: static; height: auto; padding: 24px; border-right: 0; border-bottom: 1px solid #E2E7E1; }
                    .sidebar-body { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin-top: 28px; }
                    .folder-navigation { grid-column: 1 / -1; margin-bottom: 0; }
                    .classification-navigation { margin-top: 0; grid-column: 1 / -1; }
                    .classification-navigation nav { display: grid; grid-template-columns: 1fr 1fr; }
                    .management-row { grid-template-columns: 1fr; }
                    .delete-classification { justify-self: end; }
                    .detail-registration { margin-top: 0; }
                    .genre-registration { margin: 0; padding: 0; border: 0; }
                    .main-pane { padding: 36px 24px 64px; }
                    .quick-add-controls { align-items: flex-start; }
                    .add-details-grid { grid-template-columns: minmax(0, 1fr); }
                    .todo-dialog-content { padding: 22px; }
                    .todo-dialog-fields { grid-template-columns: minmax(0, 1fr); }
                    .page-header { margin-bottom: 32px; }
                    h1 { font-size: 26px; }
                    .list-toolbar { align-items: flex-start; flex-direction: column; }
                }
                @media (prefers-reduced-motion: reduce) {
                    button, a, input, select, .sort-choice span { transition: none; }
                    .todo-add-details.is-expanding { animation: none; }
                }
                """;
    }

    // ★ 操作に使うアイコンは16px・線幅1.6の線画に統一します。
    static String uiIcon(String name) {
        String shape = switch (name) {
            case "list" ->
                "<rect x='3' y='3' width='18' height='18' rx='4'/><path d='m7 9 1.5 1.5L11 8M13 9h4M7 15h2M13 15h4'/>";
            case "filter" -> "<path d='M4 6h16M7 12h10M10 18h4'/>";
            case "sort" -> "<path d='M8 4v16m-4-4 4 4 4-4M16 20V4m-4 4 4-4 4 4'/>";
            case "plus" -> "<path d='M12 5v14M5 12h14'/>";
            case "trash" -> "<path d='M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v5M14 11v5'/>";
            case "folder" -> "<path d='M3 7V5a1 1 0 0 1 1-1h5l2 3h9a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1Z'/>";
            default -> "";
        };
        return "<svg class='icon' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.6' stroke-linecap='round' stroke-linejoin='round' aria-hidden='true'>"
                + shape + "</svg>";
    }

    static Classification classificationForKey(String key) {
        for (Classification kind : Classification.values()) if (kind.key.equals(key)) return kind;
        return null;
    }

    // ★ 表示名の余分な空白を取り除き、比較用の名前では全角・半角と英字の大小も揃えます。
    static String cleanClassificationName(String name) {
        return name.replaceAll("[\\p{Z}\\s]+", " ").strip();
    }

    static String classificationNameKey(String name) {
        return cleanClassificationName(Normalizer.normalize(name, Normalizer.Form.NFKC)).toLowerCase(Locale.ROOT);
    }

    static ClassificationItem findClassification(Connection connection, Classification kind, String name) throws SQLException {
        String key = classificationNameKey(name);
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, name FROM " + kind.table + " ORDER BY id");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                if (classificationNameKey(result.getString("name")).equals(key)) {
                    return new ClassificationItem(result.getInt("id"), result.getString("name"), 0);
                }
            }
        }
        return null;
    }

    // ★ 同じ名前があればその番号を返します。既存の番号やTodoとの対応は変えません。
    static synchronized ClassificationItem createClassification(Classification kind, String rawName) throws IOException {
        String name = cleanClassificationName(rawName);
        if (name.isEmpty()) throw new IllegalArgumentException("名前を入力してください");
        try (Connection connection = openDatabase()) {
            connection.setAutoCommit(false);
            try {
                ClassificationItem existing = findClassification(connection, kind, name);
                if (existing != null) {
                    connection.commit();
                    return existing;
                }
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + kind.table + " (name) VALUES (?)")) {
                    statement.setString(1, name);
                    statement.executeUpdate();
                }
                int id;
                try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT last_insert_rowid()")) {
                    result.next();
                    id = result.getInt(1);
                }
                connection.commit();
                return new ClassificationItem(id, name, 0);
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    // ★ 名前だけを変えるため、その分類を使用するすべてのTodoにも次の表示時に反映されます。
    static synchronized void renameClassification(Classification kind, int id, String rawName) throws IOException {
        String name = cleanClassificationName(rawName);
        if (id <= 0 || name.isEmpty()) throw new IllegalArgumentException();
        try (Connection connection = openDatabase()) {
            connection.setAutoCommit(false);
            try {
                ClassificationItem existing = findClassification(connection, kind, name);
                if (existing != null && existing.id() != id) throw new IllegalArgumentException("同じ名前が登録済みです");
                try (PreparedStatement statement = connection.prepareStatement("UPDATE " + kind.table + " SET name = ? WHERE id = ?")) {
                    statement.setString(1, name);
                    statement.setInt(2, id);
                    if (statement.executeUpdate() != 1) throw new IllegalArgumentException();
                }
                connection.commit();
            } catch (SQLException | IllegalArgumentException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    // ★ Todo本体は残し、分類との対応を解除してから分類だけを削除します。
    static synchronized void deleteClassification(Classification kind, int id) throws IOException {
        if (id <= 0) throw new IllegalArgumentException();
        try (Connection connection = openDatabase()) {
            connection.setAutoCommit(false);
            try {
                if (kind == Classification.TAG) {
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM todo_tags WHERE tag_id = ?")) {
                        statement.setInt(1, id);
                        statement.executeUpdate();
                    }
                } else {
                    try (PreparedStatement statement = connection.prepareStatement("UPDATE todos SET " + kind.column + " = NULL WHERE " + kind.column + " = ?")) {
                        statement.setInt(1, id);
                        statement.executeUpdate();
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + kind.table + " WHERE id = ?")) {
                    statement.setInt(1, id);
                    if (statement.executeUpdate() != 1) throw new IllegalArgumentException();
                }
                connection.commit();
            } catch (SQLException | IllegalArgumentException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    // ★ 使用中の件数には期限切れフォルダのTodoも含め、削除の影響を明示します。
    static List<ClassificationItem> loadClassificationItems(Classification kind) throws IOException {
        List<ClassificationItem> items = new ArrayList<>();
        String sql = kind == Classification.TAG
                ? "SELECT c.id, c.name, COUNT(t.todo_id) AS uses FROM tags c LEFT JOIN todo_tags t ON t.tag_id = c.id GROUP BY c.id ORDER BY c.name, c.id"
                : "SELECT c.id, c.name, COUNT(t.id) AS uses FROM " + kind.table + " c LEFT JOIN todos t ON t." + kind.column + " = c.id GROUP BY c.id ORDER BY c.name, c.id";
        try (Connection connection = openDatabase(); PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            while (result.next()) items.add(new ClassificationItem(result.getInt("id"), result.getString("name"), result.getInt("uses")));
        } catch (SQLException e) {
            throw new IOException(e);
        }
        return items;
    }

    static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    // ★ 関連するTodoを削除したとき、タグとの対応も自動で片付く接続にします。
    static Connection openDatabase() throws SQLException {
        Connection connection = DriverManager.getConnection(DB_URL);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    // ★ 既存データを残して、必要な表と項目を用意します。
    static void initDatabase() throws IOException { // ★ todos表を準備するメソッドです。
        try (Connection connection = openDatabase();
                Statement statement = connection.createStatement()) { // ★ SQLiteへ接続しSQL実行の準備をします。
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS genres (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)"); // ★
                                                                                                              // カテゴリを別の表に保存します。
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS todos (id INTEGER PRIMARY KEY, title TEXT, done INTEGER, deadline TEXT, genre_id INTEGER, expired_on TEXT, priority TEXT NOT NULL DEFAULT '中' CHECK (priority IN ('高','中','低')))");
            // ★ 以前の表に期限列がなければ、既存のTodoを残したまま追加します。
            boolean hasDeadline = false;
            boolean hasGenre = false;
            boolean hasExpiredOn = false;
            boolean hasPriority = false;
            try (ResultSet columns = statement.executeQuery("PRAGMA table_info(todos)")) {
                while (columns.next()) {
                    if (columns.getString("name").equals("deadline")) {
                        hasDeadline = true;
                    }
                    if (columns.getString("name").equals("genre_id")) {
                        hasGenre = true;
                    }
                    if (columns.getString("name").equals("expired_on")) {
                        hasExpiredOn = true;
                    }
                    if (columns.getString("name").equals("priority")) {
                        hasPriority = true;
                    }
                }
            }
            if (!hasDeadline) {
                statement.executeUpdate("ALTER TABLE todos ADD COLUMN deadline TEXT");
            }
            if (!hasGenre) {
                statement.executeUpdate("ALTER TABLE todos ADD COLUMN genre_id INTEGER");
            }
            if (!hasExpiredOn) {
                statement.executeUpdate("ALTER TABLE todos ADD COLUMN expired_on TEXT");
            }
            if (!hasPriority) {
                statement.executeUpdate("ALTER TABLE todos ADD COLUMN priority TEXT NOT NULL DEFAULT '中' CHECK (priority IN ('高','中','低'))");
            }
            // ★ 既存のTodoを残したまま、新しい分類用の保存先を用意します。
            List<String> existingColumns = new ArrayList<>();
            try (ResultSet columns = statement.executeQuery("PRAGMA table_info(todos)")) {
                while (columns.next()) {
                    existingColumns.add(columns.getString("name"));
                }
            }
            for (Detail detail : Detail.values()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + detail.table
                        + " (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)");
                if (!existingColumns.contains(detail.column)) {
                    statement.executeUpdate("ALTER TABLE todos ADD COLUMN " + detail.column + " INTEGER");
                }
            }
            // ★ タグ名と、Todo・タグの対応を別々の表に保存して複数設定を可能にします。
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS tags (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS todo_tags (todo_id INTEGER NOT NULL REFERENCES todos(id) ON DELETE CASCADE, tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE, PRIMARY KEY (todo_id, tag_id))");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS todo_tags_by_tag ON todo_tags (tag_id, todo_id)");
        } catch (SQLException e) { // ★ SQLのエラーを受け止めます。
            throw new IOException(e); // ★ Webサーバー側へ入出力エラーとして伝えます。
        } // ★ 接続を閉じます。
    } // ★ 表の準備を終えます。
      // ★ 期限切れは移動日を記録し、フォルダで30日間保管してから削除します。

    static void maintainExpiredTodos() throws IOException {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Tokyo"));
        try (Connection connection = openDatabase()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE todos SET expired_on = ? WHERE expired_on IS NULL AND deadline IS NOT NULL AND deadline <> '' AND deadline < ?")) {
                statement.setString(1, today.toString());
                statement.setString(2, today.toString());
                statement.executeUpdate();
            }
            // ★ すでに移動済みのTodoの移動日は書き換えません。
            try (PreparedStatement statement = connection
                    .prepareStatement("DELETE FROM todos WHERE expired_on IS NOT NULL AND expired_on <= ?")) {
                statement.setString(1, today.minusDays(30).toString());
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    // ★ サーバーが動いている間、フォルダへの移動と保管期間を確認します。
    static void startExpiryCleanup() {
        ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "todo-expiry-cleanup");
            thread.setDaemon(true); // ★ サーバー終了時に定期処理も終了できるようにします。
            return thread;
        });
        cleanup.scheduleWithFixedDelay(() -> {
            try {
                maintainExpiredTodos();
            } catch (IOException e) {
                // ★ 一度失敗しても定期処理を止めず、次の回に再試行します。
                System.err.println("期限切れTodoの整理に失敗しました: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    // ★変更 TodoをINSERT文でデータベースに追加します。
    static void addTodo(String title, int genreId) throws IOException { // ★ タイトルとカテゴリを保存します。
        addTodo(title, genreId, "中");
    }

    // ★ 重要度も同じTodoの行へ保存します。
    static void addTodo(String title, int genreId, String priority) throws IOException {
        addTodo(title, genreId, priority, Map.of());
    }

    // ★ 場所・関係者・プロジェクトの番号も、Todoと一緒に保存します。
    static void addTodo(String title, int genreId, String priority, Map<Detail, Integer> detailIds) throws IOException {
        addTodo(title, genreId, priority, detailIds, List.of());
    }

    // ★ Todo本体と複数のタグを、一組として保存します（途中で失敗したら両方を取り消します）。
    static void addTodo(String title, int genreId, String priority, Map<Detail, Integer> detailIds,
            List<Integer> tagIds) throws IOException {
        try (Connection connection = openDatabase()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection
                    .prepareStatement("INSERT INTO todos (title, done, genre_id, priority, location_id, person_id, project_id) VALUES (?, 0, ?, ?, ?, ?, ?)")) {
                statement.setString(1, title); // ★ 1つ目の ? にタイトルを入れます。
                if (genreId == 0) {
                    statement.setNull(2, java.sql.Types.INTEGER);
                } else {
                    statement.setInt(2, genreId);
                }
                statement.setString(3, priority);
                int parameter = 4;
                for (Detail detail : Detail.values()) {
                    int choiceId = detailIds.getOrDefault(detail, 0);
                    if (choiceId == 0) {
                        statement.setNull(parameter++, java.sql.Types.INTEGER);
                    } else {
                        statement.setInt(parameter++, choiceId);
                    }
                }
                statement.executeUpdate(); // ★ 新しいTodoを保存します。
                // ★ 今追加したTodoの番号は、同じ接続から受け取ります。
                int todoId;
                try (Statement idStatement = connection.createStatement();
                        ResultSet result = idStatement.executeQuery("SELECT last_insert_rowid()")) {
                    result.next();
                    todoId = result.getInt(1);
                }
                saveTodoTags(connection, todoId, tagIds);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) { // ★ SQLのエラーを受け止めます。
            throw new IOException(e); // ★ Webサーバー側へ入出力エラーとして伝えます。
        } // ★ 接続を閉じます。
    } // ★ 追加処理を終えます。
      // ★変更 指定したTodoをUPDATE文で完了状態にします。

    static void markTodoDone(int id) throws IOException { // ★ 完了にするメソッドです。
        try (Connection connection = openDatabase();
                PreparedStatement statement = connection
                        .prepareStatement("UPDATE todos SET done = 1 WHERE id = ? AND expired_on IS NULL")) { // ★
                                                                                                              // 通常のTodoを完了にします。
            statement.setInt(1, id); // ★ 1つ目の ? にTodoの番号を入れます。
            statement.executeUpdate(); // ★ 該当Todoを完了にします。
        } catch (SQLException e) { // ★ SQLのエラーを受け止めます。
            throw new IOException(e); // ★ Webサーバー側へ入出力エラーとして伝えます。
        } // ★ 接続を閉じます。
    } // ★ 完了処理を終えます。
      // ★変更 指定したTodoをDELETE文でデータベースから削除します。

    static void deleteTodo(int id) throws IOException { // ★ 削除するメソッドです。
        try (Connection connection = openDatabase();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM todos WHERE id = ?")) { // ★
                                                                                                               // DELETE文を準備します。
            statement.setInt(1, id); // ★ 1つ目の ? にTodoの番号を入れます。
            statement.executeUpdate(); // ★ 該当Todoを削除します。
        } catch (SQLException e) { // ★ SQLのエラーを受け止めます。
            throw new IOException(e); // ★ Webサーバー側へ入出力エラーとして伝えます。
        } // ★ 接続を閉じます。
    } // ★ 削除処理を終えます。
    // ★ 保管期間内のTodoだけを戻し、古い期限による即時再移動を防ぎます。
    static void restoreTodo(int id) throws IOException {
        maintainExpiredTodos();
        try (Connection connection = openDatabase(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE todos SET expired_on = NULL, deadline = NULL WHERE id = ? AND expired_on IS NOT NULL AND expired_on > ?")) {
            statement.setInt(1, id);
            statement.setString(2, LocalDate.now(ZoneId.of("Asia/Tokyo")).minusDays(30).toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }
    // ★変更 SELECT文でデータベースからTodo一覧を読み込みます。

    static List<Todo> loadTodos() throws IOException { // ★ Todo一覧を読むメソッドです。
        return loadTodos(new Filters(), "all"); // ★ JSON用の入口では保管中を含む全件を返します。
    }

    // ★ 同じ項目の選択はOR（どれか）、別項目の条件はAND（すべて）で検索します。
    static List<Todo> loadTodos(Filters filters, String folder) throws IOException {
        maintainExpiredTodos();
        List<Object> parameters = new ArrayList<>();
        List<Todo> todos = new ArrayList<>(); // ★ 読み込んだTodoを入れる一覧です。
        String sql = "SELECT t.id, t.title, t.done, t.deadline, t.genre_id, t.expired_on, t.priority, g.name AS genre_name";
        for (Detail detail : Detail.values()) {
            sql += ", t." + detail.column + ", " + detail.key + ".name AS " + detail.key + "_name";
        }
        sql += " FROM todos t LEFT JOIN genres g ON t.genre_id = g.id";
        for (Detail detail : Detail.values()) {
            sql += " LEFT JOIN " + detail.table + " " + detail.key + " ON t." + detail.column
                    + " = " + detail.key + ".id";
        }
        sql += " WHERE 1 = 1";
        if (folder.equals("expired")) {
            sql += " AND t.expired_on IS NOT NULL";
        } else if (!folder.equals("all")) {
            sql += " AND t.expired_on IS NULL";
        }
        List<Integer> statuses = new ArrayList<>();
        for (String status : filters.get("statusFilter")) {
            statuses.add(status.equals("done") ? 1 : 0);
        }
        sql += inFilter("COALESCE(t.done, 0)", statuses, parameters);
        sql += inFilter("t.priority", filters.get("priorityFilter"), parameters);
        sql += inFilter("t.genre_id", filterIds(filters, "filter"), parameters);
        for (Detail detail : Detail.values()) {
            sql += inFilter("t." + detail.column, filterIds(filters, detail.filterKey()), parameters);
        }
        if (!filters.get("tagFilter").isEmpty()) {
            // ★ タグは選んだもののどれか一つが付いていれば一致し、Todoを重複表示しません。
            sql += " AND EXISTS (SELECT 1 FROM todo_tags tt WHERE tt.todo_id = t.id"
                    + inFilter("tt.tag_id", filterIds(filters, "tagFilter"), parameters) + ")";
        }
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Tokyo"));
        List<String> deadlines = new ArrayList<>();
        for (String choice : filters.get("deadlineFilter")) {
            if (choice.equals("today")) {
                deadlines.add("t.deadline = ?");
                parameters.add(today.toString());
            } else if (choice.equals("week")) {
                deadlines.add("(t.deadline >= ? AND t.deadline <= ?)");
                parameters.add(today.toString());
                parameters.add(today.plusDays(7).toString());
            } else if (choice.equals("later")) {
                deadlines.add("t.deadline > ?");
                parameters.add(today.plusDays(7).toString());
            }
        }
        if (!deadlines.isEmpty()) {
            sql += " AND (" + String.join(" OR ", deadlines) + ")";
        }
        // ★ 未登録を後ろにし、期限が近い順、同じ期限なら番号順に読み込みます。
        sql += " ORDER BY CASE WHEN t.deadline IS NULL OR t.deadline = '' THEN 1 ELSE 0 END, t.deadline ASC, t.id ASC";
        try (Connection connection = openDatabase();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) {
                statement.setObject(i + 1, parameters.get(i));
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) { // ★ 結果を1行ずつ読みます。
                    Todo todo = new Todo(result.getInt("id"), result.getString("title")); // ★ idとタイトルからTodoを作ります。
                    todo.setDeadline(result.getString("deadline")); // ★ 保存した期限も読み戻します。
                    todo.setExpiredOn(result.getString("expired_on"));
                    todo.setGenre(result.getInt("genre_id"), result.getString("genre_name"));
                    todo.setPriority(result.getString("priority"));
                    for (Detail detail : Detail.values()) {
                        todo.setDetail(detail.key, result.getInt(detail.column), result.getString(detail.key + "_name"));
                    }
                    if (result.getInt("done") == 1) { // ★ 完了状態が1かを調べます。
                        todo.setDone(true); // ★ 完了済みとして設定します。
                    } // ★ 完了状態の確認を終えます。
                    todos.add(todo); // ★ Todoを一覧に加えます。
                } // ★ 結果の読み込みを終えます。
            }
            loadTodoTags(connection, todos);
        } catch (SQLException e) { // ★ SQLのエラーを受け止めます。
            throw new IOException(e); // ★ Webサーバー側へ入出力エラーとして伝えます。
        } // ★ 接続を閉じます。
        return todos; // ★ 読み込んだ一覧を返します。
    } // ★ 一覧の読み込みを終えます。

    // ★ INは「この一覧のどれかに一致」という検索条件です。値はSQLへ別に渡します。
    static String inFilter(String column, List<?> selected, List<Object> parameters) {
        if (selected.isEmpty()) return "";
        List<String> placeholders = new ArrayList<>();
        for (Object value : selected) {
            placeholders.add("?");
            parameters.add(value);
        }
        return " AND " + column + " IN (" + String.join(",", placeholders) + ")";
    }

    static List<Integer> filterIds(Filters filters, String key) {
        List<Integer> ids = new ArrayList<>();
        for (String value : filters.get(key)) ids.add(Integer.parseInt(value));
        return ids;
    }
      // ★ 送られたフォームの中から指定した名前の値をUTF-8で取り出します。

    static String formValue(String body, String name) {
        if (body == null) {
            return "";
        }
        for (String field : body.split("&")) {
            String[] pair = field.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(name)) {
                return pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            }
        }
        return "";
    }

    // ★ カテゴリ未選択は0、登録済みカテゴリは正の番号として扱います。
    static int readGenreId(String value) {
        if (value.isEmpty() || value.equals("0")) {
            return 0;
        }
        int id = Integer.parseInt(value);
        if (id < 1) {
            throw new NumberFormatException("カテゴリ番号は正の数です");
        }
        return id;
    }

    // ★ 選択を複数受け取り、項目ごとに同じ値を一つへまとめます。
    static Filters readFilters(String source) {
        Filters filters = new Filters();
        for (String[] field : FILTER_FIELDS) {
            String key = field[0];
            List<String> received = formValues(source, key);
            List<String> selected = new ArrayList<>();
            if (!received.contains("")) {
                for (String value : received) {
                    boolean valid;
                    switch (key) {
                        case "statusFilter" -> valid = List.of("done", "undone").contains(value);
                        case "priorityFilter" -> valid = List.of("高", "中", "低").contains(value);
                        case "deadlineFilter" -> valid = List.of("today", "week", "later").contains(value);
                        default -> {
                            try {
                                int id = Integer.parseInt(value);
                                valid = id > 0;
                                value = Integer.toString(id);
                            } catch (NumberFormatException e) {
                                valid = false;
                            }
                        }
                    }
                    if (valid && !selected.contains(value)) selected.add(value);
                }
            }
            filters.values.put(key, selected);
        }
        return filters;
    }

    // ★ 各操作のあとも、全項目の複数選択と表示中のフォルダを維持します。
    static String listLocation(Filters filters, String folder) {
        String query = filters.query();
        if (folder.equals("expired")) {
            query += (query.isEmpty() ? "" : "&") + "folder=expired";
        }
        return query.isEmpty() ? "/" : "/?" + query;
    }

    // ★ カテゴリなどの番号と名前を、チェック欄で使う文字列の対応表へ変えます。
    static Map<String, String> namedIdOptions(Map<Integer, String> source) {
        Map<String, String> options = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : source.entrySet()) {
            options.put(entry.getKey().toString(), entry.getValue());
        }
        return options;
    }

    static Map<String, String> namedOptions(String... pairs) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) options.put(pairs[i], pairs[i + 1]);
        return options;
    }

    // ★ 「すべて」に続く項目は、固定の条件か、ユーザーが登録した名前だけにします。
    static Map<String, Map<String, String>> filterOptions(Map<Integer, String> genres,
            Map<Detail, Map<Integer, String>> details, Map<Integer, String> tags) {
        Map<String, Map<String, String>> options = new LinkedHashMap<>();
        options.put("statusFilter", namedOptions("done", "完了", "undone", "未完了"));
        options.put("priorityFilter", namedOptions("高", "高", "中", "中", "低", "低"));
        options.put("deadlineFilter", namedOptions("today", "今日", "week", "一週間以内", "later", "それ以降"));
        options.put("filter", namedIdOptions(genres));
        for (Detail detail : Detail.values()) options.put(detail.filterKey(), namedIdOptions(details.get(detail)));
        options.put("tagFilter", namedIdOptions(tags));
        return options;
    }

    // ★ 削除済みなど、存在しない選択肢は引き継ぎません。
    static void retainKnownFilters(Filters filters, Map<String, Map<String, String>> options) {
        for (String[] field : FILTER_FIELDS) {
            List<String> selected = new ArrayList<>();
            for (String value : filters.get(field[0])) {
                if (options.get(field[0]).containsKey(value)) selected.add(value);
            }
            filters.values.put(field[0], selected);
        }
    }

    // ★ 分類を一つに絞っているときだけ、追加フォームでもその分類を選んでおきます。
    static int singleFilterId(Filters filters, String key) {
        return filters.get(key).size() == 1 ? Integer.parseInt(filters.get(key).get(0)) : 0;
    }

    static String filterSelectionLabel(List<String> selected, Map<String, String> options) {
        if (selected.isEmpty()) return "すべて";
        List<String> names = new ArrayList<>();
        for (String value : selected) names.add(options.get(value));
        return String.join("、", names);
    }

    // ★ 詳細フィルターの中にも、場所などを個別に開く欄を入れます。
    static String filterAccordionForm(Filters filters, Map<String, Map<String, String>> options, String folder) {
        String html = "<form id='list-filter-form' class='dialog-filter-form' method='get' action='/'>";
        for (int i = 0; i < 4; i++) {
            String[] field = FILTER_FIELDS[i];
            html += filterAccordion(field[0], field[1], options.get(field[0]), filters.get(field[0]));
        }
        html += "<details class='advanced-filters'><summary><span>詳細フィルター</span><span class='accordion-arrow' aria-hidden='true'></span></summary><div class='advanced-filter-content'>";
        for (int i = 4; i < FILTER_FIELDS.length; i++) {
            String[] field = FILTER_FIELDS[i];
            html += filterAccordion(field[0], field[1], options.get(field[0]), filters.get(field[0]));
        }
        html += "</div></details><p class='filter-help'>同じ項目はどれかに一致、別の項目はすべての条件に一致するTodoを表示します。</p>"
                + "<input type='hidden' name='folder' value='" + folder + "'>"
                + "<a class='show-all' href='" + escapeHtml(listLocation(new Filters(), folder)) + "'>すべて表示</a></form>";
        return html;
    }

    // ★ アコーディオン（押すと開閉する欄）に、複数選択できるチェック欄を入れます。
    static String filterAccordion(String key, String label, Map<String, String> options, List<String> selected) {
        String html = "<details class='filter-accordion' data-filter-group='" + key + "'><summary><span>"
                + label + "</span><span class='filter-summary' title='"
                + escapeHtml(filterSelectionLabel(selected, options)) + "'>"
                + escapeHtml(selected.size() > 1 ? selected.size() + "件選択" : filterSelectionLabel(selected, options))
                + "</span><span class='accordion-arrow' aria-hidden='true'></span></summary>"
                + "<fieldset class='filter-choices'><legend class='sr-only'>" + label + "（複数選択可）</legend>"
                + filterCheckbox(key, "", "すべて", selected.isEmpty());
        for (Map.Entry<String, String> option : options.entrySet()) {
            html += filterCheckbox(key, option.getKey(), option.getValue(), selected.contains(option.getKey()));
        }
        if (key.equals("deadlineFilter")) {
            html += "<p class='filter-help'>一週間以内：今日から7日後まで。それ以降：8日後から。</p>";
        } else if (options.isEmpty()) {
            html += "<p class='filter-help'>登録済みの" + label + "はありません。</p>";
        }
        return html + "</fieldset></details>";
    }

    static String filterCheckbox(String key, String value, String label, boolean checked) {
        return "<label class='filter-choice'><input type='checkbox' name='" + key + "' value='"
                + escapeHtml(value) + "'" + (checked ? " checked" : "") + "><span>" + escapeHtml(label) + "</span></label>";
    }

    // ★ 登録先と変更先のパス（URLの末尾）を、決めた3種類から探します。
    static Detail detailForPath(String path, boolean registration) {
        for (Detail detail : Detail.values()) {
            String expected = registration ? "/" + detail.table + "/add" : "/" + detail.key;
            if (path.equals(expected)) {
                return detail;
            }
        }
        return null;
    }

    // ★ 選択された番号は、未設定の0か登録済みの番号だけを受け付けます。
    static int readDetailId(Detail detail, String value) throws IOException {
        int id = value.isEmpty() ? 0 : Integer.parseInt(value);
        if (id < 0 || (id > 0 && !loadDetailOptions(detail).containsKey(id))) {
            throw new NumberFormatException("登録済みの番号を選んでください");
        }
        return id;
    }

    // ★ Todoの追加フォームから、新しい3項目を日本語のまま受け取ります。
    static Map<Detail, Integer> readDetailIds(String body) throws IOException {
        Map<Detail, Integer> ids = new LinkedHashMap<>();
        for (Detail detail : Detail.values()) {
            ids.put(detail, readDetailId(detail, formValue(body, detail.key)));
        }
        return ids;
    }

    // ★ 選択肢の名前は重複登録せず、Todoがなくても残しておきます。
    static void addDetail(Detail detail, String name) throws IOException {
        if (!cleanClassificationName(name).isEmpty()) createClassification(classificationForKey(detail.key), name);
    }

    // ★ 場所・関係者・プロジェクトの名前を、それぞれ名前順に読み込みます。
    static Map<Integer, String> loadDetailOptions(Detail detail) throws IOException {
        Map<Integer, String> options = new LinkedHashMap<>();
        try (Connection connection = openDatabase();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, name FROM " + detail.table + " ORDER BY name, id");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                options.put(result.getInt("id"), result.getString("name"));
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
        return options;
    }

    // ★ 3項目の選択肢をまとめて画面へ渡します。
    static Map<Detail, Map<Integer, String>> loadDetails() throws IOException {
        Map<Detail, Map<Integer, String>> details = new LinkedHashMap<>();
        for (Detail detail : Detail.values()) {
            details.put(detail, loadDetailOptions(detail));
        }
        return details;
    }

    // ★ そのTodoの選択項目だけを変更します。期限切れフォルダでは変更しません。
    static void updateDetail(int id, Detail detail, int choiceId) throws IOException {
        try (Connection connection = openDatabase();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE todos SET " + detail.column + " = ? WHERE id = ? AND expired_on IS NULL")) {
            if (choiceId == 0) {
                statement.setNull(1, java.sql.Types.INTEGER);
            } else {
                statement.setInt(1, choiceId);
            }
            statement.setInt(2, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    // ★ Todo追加時と変更時に使う、未設定も選べる選択欄です。
    static String detailSelect(Detail detail, Map<Integer, String> options, int selected) {
        return classificationCombo(classificationForKey(detail.key), options, List.of(selected));
    }

    // ★ 同じ名前の入力欄をすべて読みます。チェック欄を複数選ぶと同じ名前で送られます。
    static List<String> formValues(String body, String name) {
        List<String> values = new ArrayList<>();
        if (body != null) {
            for (String field : body.split("&")) {
                String[] pair = field.split("=", 2);
                if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(name)) {
                    values.add(pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
                }
            }
        }
        return values;
    }

    // ★ 送られたタグ番号を確認し、同じタグは一度だけ保存します。
    static List<Integer> readTagIds(String body) throws IOException {
        Map<Integer, String> tags = loadTags();
        List<Integer> ids = new ArrayList<>();
        for (String value : formValues(body, "tag")) {
            int id = Integer.parseInt(value);
            if (!tags.containsKey(id)) {
                throw new NumberFormatException("登録済みのタグを選んでください");
            }
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    // ★ タグを名前順に読み込みます。
    static Map<Integer, String> loadTags() throws IOException {
        Map<Integer, String> tags = new LinkedHashMap<>();
        try (Connection connection = openDatabase();
                PreparedStatement statement = connection.prepareStatement("SELECT id, name FROM tags ORDER BY name, id");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                tags.put(result.getInt("id"), result.getString("name"));
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
        return tags;
    }

    // ★ 名前が同じタグは増やさず、登録済みのものを使います。
    static void addTag(String name) throws IOException {
        if (!cleanClassificationName(name).isEmpty()) createClassification(Classification.TAG, name);
    }

    // ★ 選択を全解除した場合も含め、1件のTodoのタグを入れ替えます。
    static void saveTodoTags(Connection connection, int todoId, List<Integer> tagIds) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM todo_tags WHERE todo_id = ?")) {
            statement.setInt(1, todoId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO todo_tags (todo_id, tag_id) VALUES (?, ?) ON CONFLICT(todo_id, tag_id) DO NOTHING")) {
            for (int tagId : tagIds) {
                statement.setInt(1, todoId);
                statement.setInt(2, tagId);
                statement.executeUpdate();
            }
        }
    }

    // ★ 通常一覧にあるTodoだけ、複数のタグ設定をまとめて変更します。
    static void updateTags(int id, List<Integer> tagIds) throws IOException {
        try (Connection connection = openDatabase()) {
            connection.setAutoCommit(false);
            try {
                boolean editable;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id FROM todos WHERE id = ? AND expired_on IS NULL")) {
                    statement.setInt(1, id);
                    try (ResultSet result = statement.executeQuery()) {
                        editable = result.next();
                    }
                }
                if (editable) {
                    saveTodoTags(connection, id, tagIds);
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    // ★ 一覧に出すTodoのタグを一度に読み、Todoの重複表示を防ぎます。
    static void loadTodoTags(Connection connection, List<Todo> todos) throws SQLException {
        if (todos.isEmpty()) {
            return;
        }
        Map<Integer, Todo> byId = new LinkedHashMap<>();
        for (Todo todo : todos) {
            byId.put(todo.getId(), todo);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tt.todo_id, t.id, t.name FROM todo_tags tt JOIN tags t ON t.id = tt.tag_id ORDER BY t.name, t.id");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                Todo todo = byId.get(result.getInt("todo_id"));
                if (todo != null) {
                    todo.addTag(result.getInt("id"), result.getString("name"));
                }
            }
        }
    }

    // ★ タグも同じ検索欄を使い、複数選択とチップでの解除に対応します。
    static String tagPicker(Map<Integer, String> tags, List<Integer> selected) {
        return "<div class='tag-picker'><span class='field-label'>タグ（複数選択可）</span>"
                + classificationCombo(Classification.TAG, tags, selected) + "</div>";
    }

    // ★ 期限切れフォルダでは、設定されたタグだけを表示します。
    static String tagNames(Map<Integer, String> tags) {
        String html = "<div class='tag-choices'>";
        if (tags.isEmpty()) {
            html += "<span class='tag-hint'>タグなし</span>";
        }
        for (String name : tags.values()) {
            html += "<span class='tag-name'>" + escapeHtml(name) + "</span>";
        }
        return html + "</div>";
    }

    // ★ 入力した名前を、画面にそのまま表示できる形にします。
    static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    // ★ 追加フォームと各Todoで共通のカテゴリ選択欄を作ります。
    static String genreSelect(Map<Integer, String> genres, int selected) {
        return classificationCombo(Classification.CATEGORY, genres, List.of(selected));
    }

    // ★ 重要度は指定した3種類だけを受け付けます。
    static boolean isValidPriority(String priority) {
        return priority.equals("高") || priority.equals("中") || priority.equals("低");
    }

    // ★ 追加時と変更時に共通の選択欄を使います。
    static String prioritySelect(String selected) {
        String html = "<select name='priority' aria-label='重要度'>";
        for (String priority : new String[] { "高", "中", "低" }) {
            html += "<option value='" + priority + "'" + (priority.equals(selected) ? " selected" : "") + ">" + priority + "</option>";
        }
        return html + "</select>";
    }

    // ★ 重要度だけを書き換え、期限・カテゴリ・完了状態は保持します。
    static void updatePriority(int id, String priority) throws IOException {
        if (!isValidPriority(priority)) {
            return;
        }
        try (Connection connection = openDatabase(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE todos SET priority = ? WHERE id = ? AND expired_on IS NULL")) {
            statement.setString(1, priority);
            statement.setInt(2, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    // ★ カテゴリ名は重複登録せず、空のカテゴリも一覧に残しておきます。
    static void addGenre(String name) throws IOException {
        if (!cleanClassificationName(name).isEmpty()) createClassification(Classification.CATEGORY, name);
    }

    // ★ 登録済みカテゴリを名前順に読み込みます。
    static Map<Integer, String> loadGenres() throws IOException {
        Map<Integer, String> genres = new LinkedHashMap<>();
        try (Connection connection = openDatabase();
                PreparedStatement statement = connection
                        .prepareStatement("SELECT id, name FROM genres ORDER BY name, id");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                genres.put(result.getInt("id"), result.getString("name"));
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
        return genres;
    }

    // ★ Todoとカテゴリの対応を保存します。未分類なら空の値に戻します。
    static void updateGenre(int id, int genreId) throws IOException {
        try (Connection connection = openDatabase();
                PreparedStatement statement = connection
                        .prepareStatement("UPDATE todos SET genre_id = ? WHERE id = ? AND expired_on IS NULL")) {
            if (genreId == 0) {
                statement.setNull(1, java.sql.Types.INTEGER);
            } else {
                statement.setInt(1, genreId);
            }
            statement.setInt(2, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    // ★ 指定したTodoの期限だけをSQLiteに保存します。
    static void updateDeadline(int id, String deadline) throws IOException {
        try (Connection connection = openDatabase();
                PreparedStatement statement = connection
                        .prepareStatement("UPDATE todos SET deadline = ? WHERE id = ? AND expired_on IS NULL")) {
            statement.setString(1, deadline);
            statement.setInt(2, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e);
        }
        // ★ 過去の日付を設定した場合は、そのTodoを期限切れフォルダへ移します。
        maintainExpiredTodos();
    }

    // ★ Todo一覧をJSON配列の文字列に変換します。
    static String todosToJson(List<Todo> todos) { // ★ JSON作成メソッドです。
        StringBuilder json = new StringBuilder("["); // ★ JSON配列の先頭を用意します。
        for (int i = 0; i < todos.size(); i++) { // ★ Todoを順に並べます。
            Todo todo = todos.get(i); // ★ 今のTodoを取り出します。
            if (i > 0) { // ★ 2件目以降かを調べます。
                json.append(","); // ★ Todoの間にカンマを入れます。
            } // ★ カンマの処理を終えます。
            json.append("{\"title\":\"").append(escapeJson(todo.getTitle())).append("\",\"done\":")
                    .append(todo.isDone()).append("}"); // ★ titleとdoneを1件分追加します。
        } // ★ Todoを並べ終えます。
        return json.append("]").toString(); // ★ JSON配列を閉じて返します。
    } // ★ JSON作成メソッドを終えます。
      // ★ JSON文字列で特別な意味を持つ文字をエスケープします。

    static String escapeJson(String value) { // ★ 文字列変換メソッドです。
        StringBuilder escaped = new StringBuilder(); // ★ 変換後の文字を入れる場所です。
        for (int i = 0; i < value.length(); i++) { // ★ 文字を1つずつ確認します。
            char c = value.charAt(i); // ★ 今の文字を取り出します。
            switch (c) { // ★ 文字の種類で処理を選びます。
                case '"':
                    escaped.append("\\\"");
                    break; // ★ 二重引用符をエスケープします。
                case '\\':
                    escaped.append("\\\\");
                    break; // ★ バックスラッシュをエスケープします。
                case '\n':
                    escaped.append("\\n");
                    break; // ★ 改行をエスケープします。
                case '\r':
                    escaped.append("\\r");
                    break; // ★ 復帰改行をエスケープします。
                case '\t':
                    escaped.append("\\t");
                    break; // ★ タブをエスケープします。
                case '\b':
                    escaped.append("\\b");
                    break; // ★ バックスペースをエスケープします。
                case '\f':
                    escaped.append("\\f");
                    break; // ★ フォーム送りをエスケープします。
                default: // ★ そのほかの文字を処理します。
                    if (c < 0x20) { // ★ JSONでそのまま書けない制御文字か調べます。
                        String hex = Integer.toHexString(c); // ★ 文字コードを16進数にします。
                        escaped.append("\\u"); // ★ Unicode形式の始まりを追加します。
                        for (int zero = hex.length(); zero < 4; zero++) { // ★ 4桁になるまで確認します。
                            escaped.append("0"); // ★ 足りない桁をゼロで埋めます。
                        } // ★ 桁埋めを終えます。
                        escaped.append(hex); // ★ 文字コードを追加します。
                    } else { // ★ 通常の文字を処理します。
                        escaped.append(c); // ★ 文字をそのまま追加します。
                    } // ★ 文字の追加を終えます。
                    break; // ★ switchの処理を終えます。
            } // ★ 文字の種類ごとの処理を終えます。
        } // ★ 全ての文字を処理し終えます。
        return escaped.toString(); // ★ エスケープした文字列を返します。
    } // ★ 文字列変換メソッドを終えます。
} // ★変更 Appクラスを閉じます。
  // Todo 1件分の番号、タイトル、完了状態を持つクラスです。

class Todo {
    // Todoの番号を保存します。
    private final int id;
    // やることのタイトルを保存します。
    private final String title;
    // 済んだかどうかを保存します。
    private boolean done;
    // ★ 期限は年月日の文字列で持ち、未登録は空欄にします。
    private String deadline = "";
    // ★ 期限切れフォルダへ移した日を保存します。
    private String expiredOn = "";
    // ★ 重要度は初期値を「中」にします。
    private String priority = "中";
    // ★ カテゴリの番号と表示名を持ち、番号0は未分類を表します。
    private int genreId;
    private String genreName = "";
    // ★ 場所・関係者・プロジェクトの番号と表示名を持ちます（Mapは名前と値の対応表です）。
    private final Map<String, Integer> detailIds = new LinkedHashMap<>();
    private final Map<String, String> detailNames = new LinkedHashMap<>();
    // ★ 1件のTodoに複数のタグ番号と名前を保存します。
    private final Map<Integer, String> tags = new LinkedHashMap<>();

    // 番号とタイトルを受け取り、未完了のTodoを作ります。
    Todo(int id, String title) {
        this.id = id;
        this.title = title;
        this.done = false;
    }

    // Todoの番号を読み出します。
    int getId() {
        return id;
    }

    // Todoのタイトルを読み出します。
    String getTitle() {
        return title;
    }

    // Todoが済んでいるかを読み出します。
    boolean isDone() {
        return done;
    }

    // Todoの完了状態を書き換えます。
    void setDone(boolean done) {
        this.done = done;
    }

    // ★ 期限を表示や並び替えに使います。
    String getDeadline() {
        return deadline;
    }

    // ★ データベースの未登録値も、画面では空欄として扱います。
    void setDeadline(String deadline) {
        this.deadline = deadline == null ? "" : deadline;
    }

    String getExpiredOn() {
        return expiredOn;
    }

    void setExpiredOn(String expiredOn) {
        this.expiredOn = expiredOn == null ? "" : expiredOn;
    }

    String getPriority() {
        return priority;
    }

    void setPriority(String priority) {
        this.priority = priority == null ? "中" : priority;
    }

    // ★ Todoに設定したカテゴリを読み出します。
    int getGenreId() {
        return genreId;
    }

    String getGenreName() {
        return genreName;
    }

    void setGenre(int genreId, String genreName) {
        this.genreId = genreId;
        this.genreName = genreName == null ? "" : genreName;
    }

    // ★ 未設定の項目は番号0として読み出します。
    int getDetailId(String key) {
        return detailIds.getOrDefault(key, 0);
    }

    String getDetailName(String key) {
        return detailNames.getOrDefault(key, "");
    }

    void setDetail(String key, int id, String name) {
        detailIds.put(key, id);
        detailNames.put(key, name == null ? "" : name);
    }

    Map<Integer, String> getTags() {
        return tags;
    }

    void addTag(int id, String name) {
        tags.put(id, name);
    }
}
