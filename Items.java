// このプログラムの名前（クラス名）は Items です。
public class Items {
    // プログラムの実行が始まる場所です。
    public static void main(String[] args) {
        // todos という配列（複数の値を並べて持つ入れ物）に、Todoを3件入れます。
        String[] todos = {"牛乳を買う", "卵を買う", "パンを買う"};
        // i を 0 から始め、配列の件数に達するまで1つずつ進めます。
        for (int i = 0; i < todos.length; i++) {
            // i 番目のTodoを <li> と </li> で囲んで、ターミナル（文字を表示する画面）に出力します。
            System.out.println("<li>" + todos[i] + "</li>");
        }
    }
}
