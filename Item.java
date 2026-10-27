// このプログラムの名前（クラス名）は Item です。
public class Item {
    // プログラムの実行が始まる場所です。
    public static void main(String[] args) {
        // title という文字列（文字の並び）に「牛乳を買う」を入れます。
        String title = "牛乳を買う";
        // + で文字列をつなぎ、HTMLの1行分を作ります。
        String html = "<li>" + title + "</li>";
        // 作った文字列をターミナル（文字を入力・表示する画面）に1行表示します。
        System.out.println(html);
    }
}
