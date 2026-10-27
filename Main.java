// 必要な一覧機能（List）を使えるようにします。
import java.util.List;
// Listに値を追加するための道具を使えるようにします。
import java.util.ArrayList;
// 実行するプログラムの名前（クラス名）は Main です。
public class Main {
    // プログラムの実行が始まる場所です。
    public static void main(String[] args) {
        // Todoを入れる一覧（List）を用意します。
        List<Todo> todos = new ArrayList<>();
        // まだ済んでいないTodoを一覧に追加します。
        todos.add(new Todo("牛乳を買う", false));
        // 済んだTodoを一覧に追加します。
        todos.add(new Todo("ゴミを出す", true));
        // 一覧からTodoを1件ずつ取り出します。
        for (Todo todo : todos) {
            // TodoをHTMLの1行にして、ターミナル（文字を表示する画面）に出力します。
            System.out.println(todo.toItem());
        }
    }
}
// Todo 1件分のタイトルと、済んだかどうかを持つクラスです。
class Todo {
    // Todoのタイトル（内容）を保存する場所です。
    private String title;
    // Todoが済んだかどうかを保存する場所です。
    private boolean done;
    // タイトルと済んだかどうかを受け取って、Todoを作ります。
    Todo(String title, boolean done) {
        // 受け取ったタイトルをこのTodoに保存します。
        this.title = title;
        // 受け取った済んだかどうかをこのTodoに保存します。
        this.done = done;
    }
    // Todoを「<li>...</li>」の形の文字列に変えます。
    String toItem() {
        // 済んでいる場合は、タイトルの前に「[済] 」を付けます。
        if (done) {
            return "<li>[済] " + title + "</li>";
        }
        // まだ済んでいない場合は、タイトルをそのまま囲みます。
        return "<li>" + title + "</li>";
    }
}
