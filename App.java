
// Webサーバー（ブラウザからの接続を受ける仕組み）を使います。
import com.sun.net.httpserver.HttpServer;
// ポート番号を指定するための住所情報を使います。
import java.net.InetSocketAddress;
import java.net.URLDecoder; // URL形式の文字を元に戻す機能を使います。
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
import java.util.Map; // ★ ジャンルの番号と名前を対応させます。
import java.util.LinkedHashMap; // ★ 読み込んだジャンルの順序を保ちます。
import java.util.concurrent.Executors; // ★ 定期的に自動削除を実行します。
import java.util.concurrent.ScheduledExecutorService; // ★ 定期処理を動かすための標準機能です。
import java.util.concurrent.TimeUnit; // ★ 定期処理の間隔を分で指定します。
// プログラムの名前（クラス名）は App です。

public class App {
    // ★変更 SQLiteデータベースの接続先です。
    static final String DB_URL = "jdbc:sqlite:todos.db";

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
            if (path.equals("/add") && method.equals("POST")) {
                // 送信された中身をUTF-8の文字として読み取ります。
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                // ★ 複数の入力欄から、やることとジャンルを取り出します。
                String title = formValue(body, "todo");
                try {
                    int genreId = readGenreId(formValue(body, "genre"));
                    String priority = formValue(body, "priority");
                    if (priority.isEmpty()) {
                        priority = "中"; // ★ 重要度を送らない従来の追加操作にも対応します。
                    }
                    if (!title.isEmpty() && isValidPriority(priority)
                            && (genreId == 0 || loadGenres().containsKey(genreId))) {
                        addTodo(title, genreId, priority);
                    }
                } catch (NumberFormatException e) {
                    // ★ 不正なジャンル番号では追加しません。
                }
                // 追加後にトップページへ戻る場所を指定します。
                exchange.getResponseHeaders().set("Location",
                        listLocation(formValue(body, "filter"), formValue(body, "folder")));
                // 303でトップページへ移動する応答を返します。
                exchange.sendResponseHeaders(303, -1);
                // このリクエストへの応答を閉じます。
                exchange.close();
                // この分岐の処理を終えます。
                return;
                // ★ 自由な名前のジャンルを登録します。
            } else if (path.equals("/genres/add") && method.equals("POST")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String name = formValue(body, "name").strip();
                if (!name.isEmpty()) {
                    addGenre(name);
                }
                exchange.getResponseHeaders().set("Location",
                        listLocation(formValue(body, "filter"), formValue(body, "folder")));
                exchange.sendResponseHeaders(303, -1);
                exchange.close();
                return;
                // ★ 既存Todoのジャンルを変更します。
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
                        listLocation(formValue(body, "filter"), formValue(body, "folder")));
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
                        listLocation(formValue(body, "filter"), formValue(body, "folder")));
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
                        listLocation(formValue(body, "filter"), formValue(body, "folder")));
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
                exchange.getResponseHeaders().set("Location", listLocation(formValue(body, "filter"), ""));
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
                        listLocation(formValue(query, "filter"), formValue(query, "folder")));
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
                        listLocation(formValue(query, "filter"), formValue(query, "folder")));
                exchange.sendResponseHeaders(303, -1);
                exchange.close();
                return;
                // / を開いたら入力フォームとTodo一覧を表示します。
            } else if (path.equals("/")) {
                // ★ ジャンルと絞り込み条件を読み込みます。
                Map<Integer, String> genres = loadGenres();
                String filter = normalizedFilter(formValue(exchange.getRequestURI().getRawQuery(), "filter"));
                if (!filter.isEmpty() && !filter.equals("none") && !genres.containsKey(Integer.parseInt(filter))) {
                    filter = "";
                }
                boolean expiredFolder = formValue(exchange.getRequestURI().getRawQuery(), "folder").equals("expired");
                List<Todo> todos = loadTodos(filter, expiredFolder ? "expired" : "");
                // ★ 表示の組み立てだけをまとめ、既存のフォームと送信先を使います。
                message = renderPage(todos, genres, filter, expiredFolder);
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
    static String renderPage(List<Todo> todos, Map<Integer, String> genres, String filter, boolean expiredFolder) {
        String folder = expiredFolder ? "expired" : "";
        String hiddenFilter = "<input type='hidden' name='filter' value='" + filter
                + "'><input type='hidden' name='folder' value='" + folder + "'>";
        int selectedGenre = filter.isEmpty() || filter.equals("none") ? 0 : Integer.parseInt(filter);
        String currentGenre = filter.isEmpty() ? "すべて" : filter.equals("none") ? "未分類" : genres.get(selectedGenre);
        String html = "<!DOCTYPE html><html lang='ja'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1'><title>"
                + (expiredFolder ? "期限切れ | ToDoリスト" : "ToDo") + "</title>";
        // ★ 指定フォントを読み込み、読み込めない場合は端末の標準フォントを使います。
        html += "<link rel='preconnect' href='https://fonts.googleapis.com'><link rel='preconnect' href='https://fonts.gstatic.com' crossorigin><link href='https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&amp;family=Noto+Sans+JP:wght@400;500;600&amp;display=swap' rel='stylesheet'><style>"
                + pageStyles() + "</style></head><body><div class='workspace'>";
        // ★ 左側には既存のジャンル絞り込みと登録フォームを縦に配置します。
        html += "<aside class='sidebar'><div class='brand'>" + uiIcon("list")
                + "<span>ToDoリスト</span></div><div class='sidebar-body'>";
        html += "<nav class='folder-navigation' aria-label='Todoのフォルダ'><a class='folder-link"
                + (!expiredFolder ? " is-selected" : "") + "'" + (!expiredFolder ? " aria-current='page'" : "")
                + " href='" + escapeHtml(listLocation(filter, "")) + "'>" + uiIcon("list")
                + "Todo一覧</a><a class='folder-link" + (expiredFolder ? " is-selected" : "") + "'"
                + (expiredFolder ? " aria-current='page'" : "") + " href='"
                + escapeHtml(listLocation(filter, "expired")) + "'>" + uiIcon("folder") + "期限切れ</a></nav>";
        html += "<nav class='sidebar-section' aria-label='ジャンルで絞り込み'><h2 class='section-label'>" + uiIcon("filter")
                + "ジャンルで絞り込み</h2>";
        html += "<form class='filter-form' method='get' action='/'><label class='sr-only' for='genre-filter'>表示するジャンル</label><select id='genre-filter' name='filter'><option value=''>すべて</option><option value='none'"
                + (filter.equals("none") ? " selected" : "") + ">未分類</option>";
        for (Map.Entry<Integer, String> genre : genres.entrySet()) {
            html += "<option value='" + genre.getKey() + "'"
                    + (filter.equals(genre.getKey().toString()) ? " selected" : "") + ">" + escapeHtml(genre.getValue())
                    + "</option>";
        }
        html += "</select><input type='hidden' name='folder' value='" + folder
                + "'><button class='button button-secondary'>絞り込む</button><a class='show-all' href='"
                + escapeHtml(listLocation("", folder)) + "'>すべて表示</a></form></nav>";
        html += "<section class='sidebar-section genre-registration'><h2 class='section-label'>ジャンルを登録</h2><form class='genre-form' method='post' action='/genres/add'><label class='field-label' for='new-genre'>ジャンル名</label><input id='new-genre' name='name' placeholder='仕事・家事など' required>"
                + hiddenFilter
                + "<button class='button button-secondary'>ジャンルを登録</button></form></section></div></aside>";
        // ★ 右側は最大860pxの余白を持つメイン画面です。
        html += "<main class='main-pane'><div class='content'><header class='page-header'><p class='page-context'>"
                + escapeHtml(currentGenre) + "</p><h1>" + (expiredFolder ? "期限切れ" : "ToDoリスト") + "</h1>";
        if (expiredFolder) {
            html += "<p class='retention-note'>移動した日から30日後に自動削除されます。リストに戻すと期限は未登録になります。</p>";
        }
        html += "</header>";
        if (!expiredFolder) {
            html += "<section class='add-section' aria-labelledby='add-heading'><h2 id='add-heading' class='small-heading'>Todoを追加</h2><form class='add-form' method='post' action='/add'><label class='field'><span class='field-label'>やること</span><input name='todo' placeholder='やることを入力'></label><label class='field'><span class='field-label'>ジャンル</span>"
                    + genreSelect(genres, selectedGenre) + "</label><label class='field'><span class='field-label'>重要度</span>"
                    + prioritySelect("中") + "</label>" + hiddenFilter
                    + "<button class='button button-primary'>" + uiIcon("plus") + "追加</button></form></section>";
        }
        // ★ 並び替えボタンの操作はそのままに、選択状態を見分けられるようにします。
        html += "<section class='list-section" + (expiredFolder ? " archived-list" : "") + "' aria-labelledby='list-heading'><div class='list-toolbar'><h2 id='list-heading'>一覧</h2><div class='sort-control' aria-label='期限の並び順'><button type='button' data-sort='asc' aria-pressed='true' onclick='sortTodos(false)'>期限が近い順</button><button type='button' data-sort='desc' aria-pressed='false' onclick='sortTodos(true)'>期限が遠い順</button></div></div>";
        html += "<div class='column-headings' aria-hidden='true'><span>やること</span><span>ジャンル</span><span>重要度</span><span>期限</span><span class='actions-heading'>操作</span></div><ul id='todo-list' class='todo-list'>";
        if (todos.isEmpty()) {
            html += "<li class='empty-state'>"
                    + (expiredFolder ? "期限切れのTodoはありません" : filter.isEmpty() ? "やることは、いまゼロです" : "このジャンルのTodoはありません")
                    + "</li>";
        } else {
            for (Todo todo : todos) {
                String hiddenId = "<input type='hidden' name='id' value='" + todo.getId() + "'>";
                html += "<li class='task-row' data-deadline='" + escapeHtml(todo.getDeadline())
                        + "'><div class='task-title-box'><span class='task-title'>" + escapeHtml(todo.getTitle())
                        + "</span>" + (todo.isDone() ? "<span class='completion-mark'> ✔</span>" : "");
                if (expiredFolder) {
                    html += "<p class='task-note'>自動削除：" + LocalDate.parse(todo.getExpiredOn()).plusDays(30)
                            + "</p></div><span class='task-genre archived-value'>"
                            + escapeHtml(todo.getGenreId() == 0 ? "未分類" : todo.getGenreName())
                            + "</span><span class='task-priority archived-value'>" + escapeHtml(todo.getPriority())
                            + "</span><span class='task-deadline archived-value'>" + escapeHtml(todo.getDeadline())
                            + "</span>";
                } else {
                    html += "</div><form class='task-field task-genre' method='post' action='/genre' aria-label='ジャンルの変更'>"
                            + hiddenId + hiddenFilter + "<label class='control-label'><span class='sr-only'>ジャンル</span>"
                            + genreSelect(genres, todo.getGenreId())
                            + "</label><button class='save-button' aria-label='ジャンルを保存' title='ジャンルを保存'>保存</button></form>";
                    html += "<form class='task-field task-priority' method='post' action='/priority' aria-label='重要度の変更'>"
                            + hiddenId + hiddenFilter + "<label class='control-label'><span class='sr-only'>重要度</span>"
                            + prioritySelect(todo.getPriority())
                            + "</label><button class='save-button' aria-label='重要度を保存' title='重要度を保存'>保存</button></form>";
                    html += "<form class='task-field task-deadline' method='post' action='/deadline' aria-label='期限の変更'>"
                            + hiddenId + hiddenFilter
                            + "<label class='control-label'><span class='sr-only'>期限</span><input type='date' name='deadline' max='9999-12-31' value='"
                            + escapeHtml(todo.getDeadline())
                            + "'></label><button class='save-button' aria-label='期限を保存' title='期限を保存'>保存</button></form>";
                }
                html += "<div class='task-actions'>"
                        + (!expiredFolder
                                ? "<a class='complete-link' href='/done?id=" + todo.getId() + "&amp;filter=" + filter
                                        + "'>完了</a>"
                                : "<form class='restore-form' method='post' action='/restore'>" + hiddenId + hiddenFilter
                                        + "<button class='restore-button' title='期限を未登録にして通常の一覧に戻します'>リストに戻す</button></form>")
                        + "<a class='delete-link' href='/delete?id=" + todo.getId() + "&amp;filter=" + filter
                        + "&amp;folder=" + folder + "' aria-label='削除' title='削除'>" + uiIcon("trash")
                        + "</a></div></li>";
            }
        }
        html += "</ul></section></div></main></div>";
        // ★ 元の並び替えに、ボタンの選択表示だけを追加しています。
        html += """
                <script>
                function sortTodos(descending) {
                    const list = document.getElementById('todo-list');
                    const rows = Array.from(list.querySelectorAll('li[data-deadline]'));
                    rows.sort((a, b) => {
                        const x = a.dataset.deadline;
                        const y = b.dataset.deadline;
                        if (!x) return y ? 1 : 0;
                        if (!y) return -1;
                        return descending ? y.localeCompare(x) : x.localeCompare(y);
                    });
                    rows.forEach(row => list.appendChild(row));
                    document.querySelectorAll('[data-sort]').forEach(button => {
                        button.setAttribute('aria-pressed', String(button.dataset.sort === (descending ? 'desc' : 'asc')));
                    });
                }
                </script></body></html>
                """;
        return html;
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
                .sidebar input, .sidebar select { width: 100%; font-size: 13px; }
                .sidebar .button { width: 100%; min-height: 38px; font-size: 12px; }
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
                .add-form { display: grid; grid-template-columns: minmax(0, 1fr) 140px 100px auto; align-items: end; gap: 12px; }
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
                .sort-control { display: flex; gap: 3px; padding: 3px; border: 1px solid var(--line); border-radius: 10px; background: var(--background); }
                .sort-control button { min-height: 32px; padding: 5px 12px; border: 0; border-radius: 7px; background: transparent; color: #748078; font-size: 12px; }
                .sort-control button:hover { background: #E9EEE8; color: var(--text); }
                .sort-control button[aria-pressed='true'] { background: var(--main); color: var(--text); }
                .column-headings, .task-row { display: grid; grid-template-columns: minmax(0, 1fr) 144px 100px 190px 78px; gap: 12px; align-items: center; }
                .column-headings { padding: 0 0 11px; color: var(--muted); font-size: 11px; }
                .actions-heading { text-align: right; }
                .todo-list { list-style: none; padding: 0; border-top: 1px solid var(--line); }
                .task-row { min-height: 72px; padding: 16px 0; border-bottom: 1px solid var(--line); }
                .task-title-box { min-width: 0; font-size: 14px; overflow-wrap: anywhere; }
                .completion-mark { color: #617F68; }
                .task-field { display: flex; align-items: center; min-width: 0; gap: 6px; }
                .control-label { flex: 1; min-width: 0; }
                .task-field select, .task-field input { width: 100%; min-height: 34px; height: 34px; padding: 5px 6px; border-color: #E5EAE3; border-radius: 8px; font-size: 12px; }
                .task-field select:hover, .task-field input:hover { border-color: #C6D1C5; }
                .save-button { min-height: 32px; flex: 0 0 36px; padding: 4px 3px; border: 1px solid var(--line); border-radius: 8px; background: var(--main); color: #6A786D; font-size: 11px; }
                .save-button:hover { background: var(--sidebar); color: var(--text); border-color: #D4DED2; }
                .task-actions { display: flex; justify-content: flex-end; align-items: center; gap: 8px; }
                .archived-list .column-headings, .archived-list .task-row { grid-template-columns: minmax(0, 1fr) 130px 72px 132px 148px; }
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
                    .column-headings, .task-row { gap: 10px; grid-template-columns: minmax(0, 1fr) 130px 96px 174px 72px; }
                }
                @media (max-width: 960px) {
                    .column-headings { display: none; }
                    .task-row { grid-template-columns: repeat(6, minmax(0, 1fr)); row-gap: 12px; }
                    .archived-list .task-row { grid-template-columns: repeat(6, minmax(0, 1fr)); }
                    .task-title-box { grid-column: 1 / span 4; grid-row: 1; }
                    .task-actions { grid-column: 5 / span 2; grid-row: 1; }
                    .task-genre { grid-column: 1 / span 3; grid-row: 2; }
                    .task-priority { grid-column: 4 / span 3; grid-row: 2; }
                    .task-deadline { grid-column: 1 / -1; grid-row: 3; }
                    .add-form { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); }
                    .add-form .field:first-child { grid-column: 1 / -1; }
                    .add-form .button { grid-column: 1 / -1; }
                }
                @media (max-width: 700px) {
                    .workspace { display: block; }
                    .sidebar { position: static; height: auto; padding: 24px; border-right: 0; border-bottom: 1px solid #E2E7E1; }
                    .sidebar-body { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin-top: 28px; }
                    .genre-registration { margin: 0; padding: 0; border: 0; }
                    .main-pane { padding: 36px 24px 64px; }
                    .page-header { margin-bottom: 32px; }
                    h1 { font-size: 26px; }
                    .list-toolbar { align-items: flex-start; flex-direction: column; }
                }
                @media (prefers-reduced-motion: reduce) {
                    button, a, input, select { transition: none; }
                }
                """;
    }

    // ★ 操作に使うアイコンは16px・線幅1.6の線画に統一します。
    static String uiIcon(String name) {
        String shape = switch (name) {
            case "list" ->
                "<rect x='3' y='3' width='18' height='18' rx='4'/><path d='m7 9 1.5 1.5L11 8M13 9h4M7 15h2M13 15h4'/>";
            case "filter" -> "<path d='M4 6h16M7 12h10M10 18h4'/>";
            case "plus" -> "<path d='M12 5v14M5 12h14'/>";
            case "trash" -> "<path d='M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v5M14 11v5'/>";
            case "folder" -> "<path d='M3 7V5a1 1 0 0 1 1-1h5l2 3h9a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1Z'/>";
            default -> "";
        };
        return "<svg class='icon' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.6' stroke-linecap='round' stroke-linejoin='round' aria-hidden='true'>"
                + shape + "</svg>";
    }

    // ★変更 todos表を作成します。既に表があればそのまま使います。
    static void initDatabase() throws IOException { // ★ todos表を準備するメソッドです。
        try (Connection connection = DriverManager.getConnection(DB_URL);
                Statement statement = connection.createStatement()) { // ★ SQLiteへ接続しSQL実行の準備をします。
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS genres (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)"); // ★
                                                                                                              // ジャンルを別の表に保存します。
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
        } catch (SQLException e) { // ★ SQLのエラーを受け止めます。
            throw new IOException(e); // ★ Webサーバー側へ入出力エラーとして伝えます。
        } // ★ 接続を閉じます。
    } // ★ 表の準備を終えます。
      // ★ 期限切れは移動日を記録し、フォルダで30日間保管してから削除します。

    static void maintainExpiredTodos() throws IOException {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Tokyo"));
        try (Connection connection = DriverManager.getConnection(DB_URL)) {
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
    static void addTodo(String title, int genreId) throws IOException { // ★ タイトルとジャンルを保存します。
        addTodo(title, genreId, "中");
    }

    // ★ 重要度も同じTodoの行へ保存します。
    static void addTodo(String title, int genreId, String priority) throws IOException {
        try (Connection connection = DriverManager.getConnection(DB_URL);
                PreparedStatement statement = connection
                        .prepareStatement("INSERT INTO todos (title, done, genre_id, priority) VALUES (?, 0, ?, ?)")) {
            statement.setString(1, title); // ★ 1つ目の ? にタイトルを入れます。
            if (genreId == 0) {
                statement.setNull(2, java.sql.Types.INTEGER);
            } else {
                statement.setInt(2, genreId);
            }
            statement.setString(3, priority);
            statement.executeUpdate(); // ★ 新しいTodoを保存します。
        } catch (SQLException e) { // ★ SQLのエラーを受け止めます。
            throw new IOException(e); // ★ Webサーバー側へ入出力エラーとして伝えます。
        } // ★ 接続を閉じます。
    } // ★ 追加処理を終えます。
      // ★変更 指定したTodoをUPDATE文で完了状態にします。

    static void markTodoDone(int id) throws IOException { // ★ 完了にするメソッドです。
        try (Connection connection = DriverManager.getConnection(DB_URL);
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
        try (Connection connection = DriverManager.getConnection(DB_URL);
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
        try (Connection connection = DriverManager.getConnection(DB_URL); PreparedStatement statement = connection.prepareStatement(
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
        return loadTodos("", "all"); // ★ JSON用の入口では保管中を含む全件を返します。
    }

    // ★ ジャンルを選んだ場合だけ、保存先で絞り込んで読みます。
    static List<Todo> loadTodos(String filter) throws IOException {
        return loadTodos(filter, "");
    }

    // ★ 通常一覧と期限切れフォルダを分けて読み込みます。
    static List<Todo> loadTodos(String filter, String folder) throws IOException {
        maintainExpiredTodos();
        filter = normalizedFilter(filter);
        List<Todo> todos = new ArrayList<>(); // ★ 読み込んだTodoを入れる一覧です。
        String sql = "SELECT t.id, t.title, t.done, t.deadline, t.genre_id, t.expired_on, t.priority, g.name AS genre_name FROM todos t LEFT JOIN genres g ON t.genre_id = g.id WHERE 1 = 1";
        if (folder.equals("expired")) {
            sql += " AND t.expired_on IS NOT NULL";
        } else if (!folder.equals("all")) {
            sql += " AND t.expired_on IS NULL";
        }
        if (filter.equals("none")) {
            sql += " AND t.genre_id IS NULL";
        } else if (!filter.isEmpty()) {
            sql += " AND t.genre_id = ?";
        }
        // ★ 未登録を後ろにし、期限が近い順、同じ期限なら番号順に読み込みます。
        sql += " ORDER BY CASE WHEN t.deadline IS NULL OR t.deadline = '' THEN 1 ELSE 0 END, t.deadline ASC, t.id ASC";
        try (Connection connection = DriverManager.getConnection(DB_URL);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (!filter.isEmpty() && !filter.equals("none")) {
                statement.setInt(1, Integer.parseInt(filter));
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) { // ★ 結果を1行ずつ読みます。
                    Todo todo = new Todo(result.getInt("id"), result.getString("title")); // ★ idとタイトルからTodoを作ります。
                    todo.setDeadline(result.getString("deadline")); // ★ 保存した期限も読み戻します。
                    todo.setExpiredOn(result.getString("expired_on"));
                    todo.setGenre(result.getInt("genre_id"), result.getString("genre_name"));
                    todo.setPriority(result.getString("priority"));
                    if (result.getInt("done") == 1) { // ★ 完了状態が1かを調べます。
                        todo.setDone(true); // ★ 完了済みとして設定します。
                    } // ★ 完了状態の確認を終えます。
                    todos.add(todo); // ★ Todoを一覧に加えます。
                } // ★ 結果の読み込みを終えます。
            }
        } catch (SQLException e) { // ★ SQLのエラーを受け止めます。
            throw new IOException(e); // ★ Webサーバー側へ入出力エラーとして伝えます。
        } // ★ 接続を閉じます。
        return todos; // ★ 読み込んだ一覧を返します。
    } // ★ 一覧の読み込みを終えます。
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

    // ★ ジャンル未選択は0、登録済みジャンルは正の番号として扱います。
    static int readGenreId(String value) {
        if (value.isEmpty() || value.equals("0")) {
            return 0;
        }
        int id = Integer.parseInt(value);
        if (id < 1) {
            throw new NumberFormatException("ジャンル番号は正の数です");
        }
        return id;
    }

    // ★ 絞り込み値は「すべて」「未分類」「正のジャンル番号」に限定します。
    static String normalizedFilter(String filter) {
        if (filter.equals("none")) {
            return "none";
        }
        try {
            int id = readGenreId(filter);
            return id == 0 ? "" : Integer.toString(id);
        } catch (NumberFormatException e) {
            return "";
        }
    }

    // ★ 各操作の後も同じジャンルの一覧へ戻します。
    static String listLocation(String filter) {
        return listLocation(filter, "");
    }

    // ★ 削除や絞り込みの後も、同じフォルダへ戻ります。
    static String listLocation(String filter, String folder) {
        filter = normalizedFilter(filter);
        String query = filter.isEmpty() ? "" : "filter=" + filter;
        if (folder.equals("expired")) {
            query += (query.isEmpty() ? "" : "&") + "folder=expired";
        }
        return query.isEmpty() ? "/" : "/?" + query;
    }

    // ★ 入力したジャンル名を画面にそのまま表示できる形にします。
    static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    // ★ 追加フォームと各Todoで共通のジャンル選択欄を作ります。
    static String genreSelect(Map<Integer, String> genres, int selected) {
        String html = "<select name='genre'><option value='0'" + (selected == 0 ? " selected" : "") + ">未分類</option>";
        for (Map.Entry<Integer, String> genre : genres.entrySet()) {
            html += "<option value='" + genre.getKey() + "'" + (selected == genre.getKey() ? " selected" : "") + ">"
                    + escapeHtml(genre.getValue()) + "</option>";
        }
        return html + "</select>";
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

    // ★ 重要度だけを書き換え、期限・ジャンル・完了状態は保持します。
    static void updatePriority(int id, String priority) throws IOException {
        if (!isValidPriority(priority)) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(DB_URL); PreparedStatement statement = connection.prepareStatement(
                "UPDATE todos SET priority = ? WHERE id = ? AND expired_on IS NULL")) {
            statement.setString(1, priority);
            statement.setInt(2, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    // ★ ジャンル名は重複登録せず、空のジャンルも一覧に残しておきます。
    static void addGenre(String name) throws IOException {
        try (Connection connection = DriverManager.getConnection(DB_URL);
                PreparedStatement statement = connection
                        .prepareStatement("INSERT INTO genres (name) VALUES (?) ON CONFLICT(name) DO NOTHING")) {
            statement.setString(1, name);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    // ★ 登録済みジャンルを名前順に読み込みます。
    static Map<Integer, String> loadGenres() throws IOException {
        Map<Integer, String> genres = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(DB_URL);
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

    // ★ Todoとジャンルの対応を保存します。未分類なら空の値に戻します。
    static void updateGenre(int id, int genreId) throws IOException {
        try (Connection connection = DriverManager.getConnection(DB_URL);
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
        try (Connection connection = DriverManager.getConnection(DB_URL);
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
    // ★ ジャンルの番号と表示名を持ち、番号0は未分類を表します。
    private int genreId;
    private String genreName = "";

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

    // ★ Todoに設定したジャンルを読み出します。
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
}
