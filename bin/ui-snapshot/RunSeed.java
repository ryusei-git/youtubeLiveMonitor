import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/**
 * bin/ui-snapshot.sh が、確認用インスタンス（bin/sandbox.sh）の H2 へ seed.sql を流すための小さな起動口。
 *
 * <p>H2 に付属の {@code org.h2.tools.RunScript} をコマンドとして動かすと、DB のパスワードを {@code -password} で
 * 引数に渡すことになり、動いている間は同じ端末のほかのユーザーからプロセスの一覧で読める
 * （パスワードを付けた理由は application.yml の datasource.password と #240）。ここでは環境変数から読んで渡す。
 * {@code java -cp <h2 の jar> RunSeed.java} の形で、コンパイルせずにそのまま動かす（Java 11 以降のソースファイル起動）。
 *
 * <p>使い方: {@code SPRING_DATASOURCE_PASSWORD=... java -cp h2.jar RunSeed.java <JDBC URL> <SQL ファイル>}
 */
public class RunSeed {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("使い方: java -cp h2.jar RunSeed.java <JDBC URL> <SQL ファイル>");
            System.exit(2);
        }
        String password = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(args[0], "sa", password);
             Reader script = Files.newBufferedReader(Path.of(args[1]), StandardCharsets.UTF_8)) {
            org.h2.tools.RunScript.execute(connection, script);
        }
    }
}
