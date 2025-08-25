import java.sql.Connection;
import java.sql.SQLException;

public class TestDB {
    public static void main(String[] args) {
        try (Connection con = DBUtil.getConnection()) {
            System.out.println("Connected to DB successfully!");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
