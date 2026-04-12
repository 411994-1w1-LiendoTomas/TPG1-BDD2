import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import redis.clients.jedis.Jedis;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;

public class App {

    static Jedis jedis = new Jedis("localhost", 6379);
    // ID autoincrementado gestionado por Redis (INCR seq:producto) — thread-safe y persistente.

    /** Log de debug con timestamp. Prefija [TiendaTech] para filtrar fácil en consola. */
    static void log(String nivel, String msg) {
        System.out.printf("[TiendaTech][%s] %s%n", nivel, msg);
    }

    public static void main(String[] args) throws Exception {
        cargarDatosIniciales();
        cargarUsuariosIniciales();

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/api/login",    App::handleLogin);
        server.createContext("/api/productos", App::handleProductos);
        server.createContext("/api/reportes",  App::handleReportes);
        server.createContext("/api/logs",      App::handleLogs);
        server.createContext("/app.js",        App::handleJs);
        server.createContext("/",              App::handleStatic);
        server.start();

        System.out.println("=== TiendaTech corriendo en http://localhost:8080 ===");
        System.out.println("Presiona Ctrl+C para detener.");
    }

    static void handleJs(HttpExchange ex) throws IOException {
        addCors(ex);
        ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        File f = new File("src/app.js");
        byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
        ex.getResponseHeaders().set("Content-Type", "application/javascript; charset=UTF-8");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    static void handleLogin(HttpExchange ex) throws IOException {
        addCors(ex);
        if (ex.getRequestMethod().equals("OPTIONS")) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        if (ex.getRequestMethod().equals("POST")) {
            String body = new String(ex.getRequestBody().readAllBytes(), "UTF-8");
            Map<String, String> data = parseJson(body);
            String username = data.get("username");
            String password = data.get("password");
            // Defensivo: rechazar si vienen nulos o vacíos
            if (username == null || username.trim().isEmpty() ||
                password == null || password.isEmpty()) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"Usuario y contraseña requeridos\"}");
                return;
            }
            String key = "usuario:" + username.trim();
            if (jedis.exists(key)) {
                String storedPassword = jedis.hget(key, "password");
                String rol = jedis.hget(key, "rol");
                if (password.equals(storedPassword)) {
                    log("INFO", "Login OK: " + username + " [" + rol + "]");
                    respond(ex, 200, "{\"ok\":true,\"rol\":\"" + rol + "\",\"username\":\"" + username.trim() + "\"}");
                    return;
                }
            }
            log("WARN", "Login fallido: " + username);
            respond(ex, 401, "{\"ok\":false,\"error\":\"Credenciales inválidas\"}");
        } else {
            // Método no soportado (GET, PUT, etc.) — no dejar la conexión colgada
            respond(ex, 405, "{\"error\":\"Método no permitido\"}");
        }
    }

    /**
     * Extrae el rol del header Authorization y verifica que el usuario exista en Redis.
     * Formato esperado: "Bearer ROL:username"
     */
    static String getRol(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7);
            int colonIndex = token.indexOf(':');
            if (colonIndex > 0) {
                String rol      = token.substring(0, colonIndex);
                String username = token.substring(colonIndex + 1);
                // Verificar que el usuario realmente existe en Redis
                if (!jedis.exists("usuario:" + username)) return null;
                String storedRol = jedis.hget("usuario:" + username, "rol");
                if (!rol.equals(storedRol)) return null;
                return rol;
            }
        }
        return null;
    }

    static boolean tienePermiso(String rol, String metodo, String accion) {
        if (rol == null) return false;
        switch (rol) {
            case "ADMIN":
                return true;
            case "MANAGER":
                return !accion.equals("delete");
            case "EMPLOYEE":
                return metodo.equals("GET");
            default:
                return false;
        }
    }

    static void handleReportes(HttpExchange ex) throws IOException {
        addCors(ex);
        // OPTIONS debe responderse ANTES de verificar auth (preflight del browser)
        if (ex.getRequestMethod().equals("OPTIONS")) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        String rol = getRol(ex);
        if (rol == null) {
            respond(ex, 401, "{\"error\":\"No autenticado\"}");
            return;
        }
        if (!rol.equals("ADMIN")) {
            respond(ex, 403, "{\"error\":\"Solo ADMIN puede ver reportes\"}");
            return;
        }
        if (ex.getRequestMethod().equals("GET")) {
            // Activos: usando el Set de Redis
            Set<String> activosKeys = jedis.smembers("productos:activos");
            int activos = activosKeys.size();

            // Discontinuados: total - activos
            int total = jedis.keys("producto:*").size();
            int discontinuados = Math.max(0, total - activos); // guard: nunca negativo

            int stockTotal = 0;
            int valorTotal = 0;
            int stockBajo = 0;
            Map<String, Integer> categorias = new LinkedHashMap<>();

            for (String key : activosKeys) {
                Map<String, String> p = jedis.hgetAll(key);
                if (p.isEmpty()) continue;
                int stock  = Math.max(0, parseSafe(p.getOrDefault("stock",  "0")));
                int precio = Math.max(0, parseSafe(p.getOrDefault("precio", "0")));
                stockTotal += stock;
                valorTotal += precio;
                if (stock < 6) stockBajo++;
                String cat = p.getOrDefault("categoria", "Sin categoria");
                categorias.put(cat, categorias.getOrDefault(cat, 0) + 1);
            }

            // Serializar categorias manualmente
            StringBuilder catJson = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Integer> e : categorias.entrySet()) {
                if (!first) catJson.append(",");
                catJson.append("\"").append(esc(e.getKey())).append("\":").append(e.getValue());
                first = false;
            }
            catJson.append("}");

            String json = "{\"activos\":" + activos +
                    ",\"discontinuados\":" + discontinuados +
                    ",\"stockTotal\":" + stockTotal +
                    ",\"valorTotal\":" + valorTotal +
                    ",\"stockBajo\":" + stockBajo +
                    ",\"categorias\":" + catJson + "}";
            respond(ex, 200, json);
        } else {
            respond(ex, 405, "{\"error\":\"M\u00e9todo no permitido\"}");
        }
    }

    static void handleProductos(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String rol = getRol(ex);
        addCors(ex);

        if (method.equals("OPTIONS")) { ex.sendResponseHeaders(204, -1); return; }

        if (method.equals("GET")) {
            getProductos(ex);
            return;
        }
        if (method.equals("POST")) {
            if (!tienePermiso(rol, "POST", "create")) {
                respond(ex, 403, "{\"error\":\"No tienes permiso para agregar productos\"}");
                return;
            }
            postProducto(ex);
            return;
        }
        if (method.equals("PUT")) {
            if (!tienePermiso(rol, "PUT", "update")) {
                respond(ex, 403, "{\"error\":\"No tienes permiso para modificar productos\"}");
                return;
            }
            putProducto(ex, path);
            return;
        }
        if (method.equals("DELETE")) {
            if (!tienePermiso(rol, "DELETE", "delete")) {
                respond(ex, 403, "{\"error\":\"No tienes permiso para borrar productos\"}");
                return;
            }
            deleteProducto(ex, path);
            return;
        }
        // Método no soportado
        respond(ex, 405, "{\"error\":\"Método no permitido\"}");
    }

    static void handleStatic(HttpExchange ex) throws IOException {
        addCors(ex);
        File f = new File("src/index.html");
        FileInputStream is = new FileInputStream(f);
        byte[] bytes = is.readAllBytes();
        is.close();
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    static void getProductos(HttpExchange ex) throws IOException {
        String activo = ex.getRequestHeaders().getFirst("X-Filtro-Activo");
        Set<String> keys = jedis.keys("producto:*");
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String key : keys) {
            Map<String, String> p = jedis.hgetAll(key);
            if (p.isEmpty()) continue;
            String prodActivo = p.getOrDefault("activo", "true");
            if (activo != null) {
                if (!activo.equals(prodActivo)) continue;
            } else {
                if (!"true".equals(prodActivo)) continue;
            }
            if (!first) sb.append(",");
            first = false;
            sb.append(toJson(key, p));
        }
        sb.append("]");
        respond(ex, 200, sb.toString());
    }

    /** Crea un producto nuevo. Valida campos obligatorios, rangos y longitud. */
    static void postProducto(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), "UTF-8");
        Map<String, String> data = parseJson(body);
        String nombre = data.getOrDefault("nombre", "").trim();
        String marca  = data.getOrDefault("marca",  "").trim();
        String precio = data.getOrDefault("precio", "").trim();
        String stock  = data.getOrDefault("stock",  "").trim();
        // Obligatorios
        if (nombre.isEmpty() || marca.isEmpty()) {
            respond(ex, 400, "{\"error\":\"Nombre y marca son obligatorios\"}"); return;
        }
        // Longitud máxima
        if (nombre.length() > 100 || marca.length() > 50) {
            respond(ex, 400, "{\"error\":\"Nombre máx 100 caracteres, marca máx 50\"}"); return;
        }
        int precioInt = parseSafe(precio);
        int stockInt  = parseSafe(stock);
        // Formato numérico
        if (precioInt < 0 || stockInt < 0) {
            respond(ex, 400, "{\"error\":\"Precio y stock deben ser números válidos\"}"); return;
        }
        // Rangos
        if (precioInt == 0) {
            respond(ex, 400, "{\"error\":\"El precio debe ser mayor a 0\"}"); return;
        }
        if (stockInt > 10000) {
            respond(ex, 400, "{\"error\":\"Stock máximo permitido: 10000\"}"); return;
        }
        String key = "producto:" + jedis.incr("seq:producto"); // ID atómico desde Redis
        jedis.hset(key, "nombre",    nombre);
        jedis.hset(key, "categoria", data.getOrDefault("categoria", ""));
        jedis.hset(key, "marca",     marca);
        jedis.hset(key, "precio",    String.valueOf(precioInt));
        jedis.hset(key, "stock",     String.valueOf(stockInt));
        jedis.hset(key, "activo",    "true"); // siempre inicializar explicitamente
        jedis.sadd("productos:activos", key);
        registrarLog("CREATE", nombre, getUsername(ex));
        log("INFO", "POST producto: " + key + " | " + nombre);
        respond(ex, 201, "{\"ok\":true,\"key\":\"" + key + "\"}");
    }

    /** Modifica un producto existente. Valida rangos de precio y stock. */
    static void putProducto(HttpExchange ex, String path) throws IOException {
        String key = path.replace("/api/productos/", "producto:");
        if (!jedis.exists(key)) {
            respond(ex, 404, "{\"error\":\"Producto no encontrado\"}"); return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), "UTF-8");
        Map<String, String> data = parseJson(body);
        // Validar numéricos y rangos si vienen
        if (data.containsKey("precio")) {
            int p = parseSafe(data.get("precio"));
            if (p <= 0) { respond(ex, 400, "{\"error\":\"El precio debe ser mayor a 0\"}"); return; }
        }
        if (data.containsKey("stock")) {
            int s = parseSafe(data.get("stock"));
            if (s < 0)     { respond(ex, 400, "{\"error\":\"Stock no puede ser negativo\"}");    return; }
            if (s > 10000) { respond(ex, 400, "{\"error\":\"Stock máximo permitido: 10000\"}"); return; }
        }
        if (data.containsKey("nombre") && data.get("nombre").trim().isEmpty()) {
            respond(ex, 400, "{\"error\":\"El nombre no puede estar vacío\"}"); return;
        }
        // Excluir campo 'activo' del PUT (se maneja solo con DELETE)
        data.remove("activo");
        // Excluir campos vacíos después de trim para no corromper datos en Redis
        data.entrySet().removeIf(e -> e.getValue() == null || e.getValue().trim().isEmpty());
        // Capturar nombre ANTES del update para el log (puede cambiar en este PUT)
        String nombrePrevio = jedis.hget(key, "nombre");
        for (String k : data.keySet()) {
            jedis.hset(key, k, data.get(k).trim());
        }
        registrarLog("UPDATE", nombrePrevio != null ? nombrePrevio : key, getUsername(ex));
        log("INFO", "PUT producto: " + key);
        respond(ex, 200, "{\"ok\":true}");
    }

    static void deleteProducto(HttpExchange ex, String path) throws IOException {
        String key = path.replace("/api/productos/", "producto:");
        if (!jedis.exists(key)) {
            respond(ex, 404, "{\"error\":\"Producto no encontrado\"}"); return;
        }
        if ("false".equals(jedis.hget(key, "activo"))) {
            respond(ex, 409, "{\"error\":\"El producto ya está discontinuado\"}"); return;
        }
        jedis.hset(key, "activo", "false");
        jedis.srem("productos:activos", key);
        String nombreDel = jedis.hget(key, "nombre");
        registrarLog("DELETE", nombreDel != null ? nombreDel : key, getUsername(ex));
        log("INFO", "DELETE (l\u00f3gico) producto: " + key);
        respond(ex, 200, "{\"ok\":true}");
    }

    /**
     * Extrae el username del header Authorization.
     * Formato: "Bearer ROL:username"
     */
    static String getUsername(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7);
            int colonIndex = token.indexOf(':');
            if (colonIndex > 0) return token.substring(colonIndex + 1);
        }
        return "sistema";
    }

    /**
     * Registra una entrada de auditoría en Redis como JSON string.
     * Formato: {"accion":"CREATE","detalle":"...","usuario":"...","timestamp":"HH:mm"}
     * Mantiene solo los últimos 10 registros con LPUSH + LTRIM.
     */
    static void registrarLog(String accion, String detalle, String usuario) {
        String hora    = String.format("%tR", new java.util.Date());
        String entrada = "{\"accion\":\""  + esc(accion)  + "\"," +
                          "\"detalle\":\"" + esc(detalle) + "\"," +
                          "\"usuario\":\"" + esc(usuario) + "\"," +
                          "\"timestamp\":\"" + hora + "\"}";
        jedis.lpush("logs:operaciones", entrada);
        jedis.ltrim("logs:operaciones", 0, 9); // conservar solo los últimos 10
    }

    /**
     * Endpoint GET /api/logs.
     * Requiere autenticación: ADMIN o MANAGER. Devuelve array JSON de objetos de auditoría.
     */
    static void handleLogs(HttpExchange ex) throws IOException {
        addCors(ex);
        if (ex.getRequestMethod().equals("OPTIONS")) { ex.sendResponseHeaders(204, -1); return; }
        // Autenticación y autorización
        String rol = getRol(ex);
        if (rol == null) {
            respond(ex, 401, "{\"error\":\"No autenticado\"}"); return;
        }
        if (!rol.equals("ADMIN") && !rol.equals("MANAGER")) {
            respond(ex, 403, "{\"error\":\"Solo ADMIN y MANAGER pueden ver los logs\"}"); return;
        }
        if (ex.getRequestMethod().equals("GET")) {
            java.util.List<String> logs = jedis.lrange("logs:operaciones", 0, 9);
            // Cada entrada ya es un JSON object — concatenar directamente en el array
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (String entry : logs) {
                if (!first) sb.append(",");
                sb.append(entry); // entry es un JSON string, no necesita comillas extra
                first = false;
            }
            sb.append("]");
            respond(ex, 200, sb.toString());
        } else {
            respond(ex, 405, "{\"error\":\"Método no permitido\"}");
        }
    }

    static void cargarDatosIniciales() {
        if (!jedis.keys("producto:*").isEmpty()) {
            System.out.println("Base de datos ya tiene datos, no se recarga.");
            // Migración: inicializar seq:producto en Redis si aún no existe
            if (!jedis.exists("seq:producto")) {
                long maxId = 0;
                for (String k : jedis.keys("producto:*")) {
                    try {
                        long id = Long.parseLong(k.split(":")[1]);
                        if (id > maxId) maxId = id;
                    } catch (NumberFormatException ignored) {}
                }
                jedis.set("seq:producto", String.valueOf(maxId));
                System.out.println("[TiendaTech] seq:producto inicializado en " + maxId);
            }
            return;
        }
        System.out.println("Cargando datos iniciales...");
        insertarProducto("Notebook Lenovo IdeaPad 3", "Notebooks", "Lenovo", "800000", "5");
        insertarProducto("Samsung Galaxy S23", "Celulares", "Samsung", "1200000", "8");
        insertarProducto("Auriculares Bluetooth Sony", "Audio", "Sony", "50000", "15");
        insertarProducto("Mouse Gamer Logitech", "Perifericos", "Logitech", "25000", "20");
        insertarProducto("Smart TV LG 50 pulgadas", "Televisores", "LG", "600000", "4");
        insertarProducto("Teclado Mecanico Redragon", "Perifericos", "Redragon", "70000", "10");
        insertarProducto("Tablet Samsung Galaxy Tab A8", "Tablets", "Samsung", "300000", "7");
        insertarProducto("Monitor LG 24 pulgadas", "Monitores", "LG", "200000", "6");
        System.out.println("8 productos cargados.");
    }

    static void cargarUsuariosIniciales() {
        if (jedis.exists("usuario:admin")) {
            System.out.println("Usuarios ya existen.");
            return;
        }
        System.out.println("Cargando usuarios...");
        jedis.hset("usuario:admin", "password", "admin123");
        jedis.hset("usuario:admin", "rol", "ADMIN");
        jedis.hset("usuario:manager", "password", "manager123");
        jedis.hset("usuario:manager", "rol", "MANAGER");
        jedis.hset("usuario:employee", "password", "employee123");
        jedis.hset("usuario:employee", "rol", "EMPLOYEE");
        System.out.println("Usuarios cargados: admin, manager, employee");
    }

    static void insertarProducto(String nombre, String cat, String marca, String precio, String stock) {
        String key = "producto:" + jedis.incr("seq:producto"); // ID atómico desde Redis
        jedis.hset(key, "nombre", nombre);
        jedis.hset(key, "categoria", cat);
        jedis.hset(key, "marca", marca);
        jedis.hset(key, "precio", precio);
        jedis.hset(key, "stock", stock);
        jedis.hset(key, "activo", "true");
        jedis.sadd("productos:activos", key);
    }

    static String toJson(String key, Map<String, String> p) {
        String id = key.split(":")[1];
        return "{\"id\":\"" + id + "\"," +
                "\"nombre\":\"" + esc(p.getOrDefault("nombre", "")) + "\"," +
                "\"categoria\":\"" + esc(p.getOrDefault("categoria", "")) + "\"," +
                "\"marca\":\"" + esc(p.getOrDefault("marca", "")) + "\"," +
                "\"precio\":" + p.getOrDefault("precio", "0") + "," +
                "\"stock\":" + p.getOrDefault("stock", "0") + "," +
                "\"activo\":\"" + p.getOrDefault("activo", "true") + "\"}";
    }

    static Map<String, String> parseJson(String json) {
        Map<String, String> map = new HashMap<>();
        json = json.trim().replaceAll("[{}]", "");
        for (String pair : json.split(",")) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                String k = kv[0].trim().replaceAll("\"", "");
                String v = kv[1].trim().replaceAll("\"", "");
                map.put(k, v);
            }
        }
        return map;
    }

    static String esc(String s) {
        return s.replace("\"", "\\\"");
    }

    /**
     * Parsea un String a int de forma segura.
     * Devuelve -1 si el valor es nulo, vacío o no numérico (usado para validaciones).
     * Devuelve 0 para strings que son "0".
     */
    static int parseSafe(String s) {
        if (s == null || s.trim().isEmpty()) return -1;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return -1; }
    }

    static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    static void addCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type,Authorization,X-Filtro-Activo");
    }

}