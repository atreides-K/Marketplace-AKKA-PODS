package pods.akka;


import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;



public class LoadProduct {

    public static List<String[]> loadProducts(String fileName) {
        List<String[]> products = new ArrayList<>();
        System.out.println("Current program path: " + System.getProperty("user.dir"));
        try {
            java.nio.file.Files.list(java.nio.file.Paths.get(System.getProperty("user.dir")))
            .forEach(path -> System.out.println(path.getFileName()));
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        try (InputStream inputStream = LoadProduct.class.getClassLoader().getResourceAsStream("products.csv")) {
            if (inputStream == null) {
                throw new IllegalArgumentException("File not found: " + fileName);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            
            String line;
            while ((line = reader.readLine()) != null) {
                String[] productDetails = line.split(",");
                if (productDetails.length == 5) { // Ensure all fields are present
                    products.add(productDetails);
                } else {
                    System.err.println("Invalid product entry: " + line);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return products;
    }
}
