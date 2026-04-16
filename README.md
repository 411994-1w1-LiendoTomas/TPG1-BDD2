# Tecnicatura Universitaria en Programación - Bases de Datos II - Trabajo Práctico Grupal N°1

## Integrantes

Franco del Giudice - 412427
Tomás Agustín Liendo - 411994
Manuel Marquez - 412569
Federico Navarrete - 412090

# TiendaTech - Gestión de Inventario

Sistema de gestión de inventario para una tienda de tecnología, desarrollado como trabajo práctico de la materia **Bases de Datos II**.

## Descripción

TiendaTech permite administrar el catálogo de productos de una tienda de tecnología, con control de stock, categorización por marca, precios y un sistema de usuarios con diferentes roles y permisos.

## Tecnologías

| Componente    | Tecnología                             |
|---------------|----------------------------------------|
| Backend       | Java (JDK 11+) con HttpServer embebido |
| Base de datos | Redis                                  |
| Frontend      | HTML5, CSS3, JavaScript (Vanilla)      |
| API           | REST (JSON over HTTP)                  |
| Cliente Redis | Jedis                                  |

## Funcionalidades

### Gestión de Productos
- Agregar productos (nombre, categoría, marca, precio, stock)
- Editar productos existentes
- Eliminar productos (borrado lógico)
- Reactivar productos discontinuados
- Ver productos discontinuados
- Filtrar por categoría y stock bajo
- Buscar productos por nombre o marca
- Ordenar por precio, stock o alfabéticamente

### Sistema de Usuarios y Permisos

| Rol          | Permisos                                                       |
|--------------|----------------------------------------------------------------|
| **ADMIN**    | Ver, agregar, editar, eliminar productos. Ver reportes y logs. |
| **MANAGER**  | Ver, agregar, editar productos.                                |
| **EMPLOYEE** | Solo ver productos.                                            |

### Reportes (solo ADMIN)
- Total de productos activos y discontinuados
- Stock total
- Valor total del inventario
- Productos con stock bajo (< 6 unidades)
- Distribución por categorías

### Auditoría
- Registro de actividad (insert, update, delete, reactivate)
- Logs por usuario y acción

## Usuarios precargados

| Usuario  | Contraseña   | Rol       |
|----------|--------------|-----------|
| admin    | admin123     | ADMIN     |
| manager  | manager123   | MANAGER   |
| employee | employee123  | EMPLOYEE  |

## Requisitos

- **Java JDK 11** o superior
- **Redis Server** corriendo en `localhost:6379`
- **Docker** (opcional, para ejecutar Redis)

## Instalación y ejecución

### 1. Iniciar Redis

Con Docker:
```powershell
docker run -d -p 6379:6379 --name redis redis:latest
```

Sin Docker, instalar Redis Server desde https://redis.io/download

### 2. Compilar

```powershell
javac -cp "lib/*" src\App.java
```

### 3. Ejecutar

```powershell
java -cp "src;lib/*" App
```

### 4. Abrir la aplicación

Navegar a: http://localhost:8080

## Estructura del proyecto

```
MiFork/
├── src/
│   ├── App.java       # Servidor REST y lógica de negocio
│   ├── app.js         # Frontend (JavaScript)
│   ├── index.html     # Interfaz de usuario
│   └── Server.java    # Reservado para uso futuro
├── lib/               # Dependencias (Jedis, Gson, SLF4J, etc.)
├── bin/               # Archivos compilados (.class)
├── .vscode/           # Configuración del IDE
└── README.md          # Este archivo
```

## Modelo de datos (Redis)

### Producto
```
Key: producto:{id}
Campos: nombre, categoria, marca, precio, stock, activo
Set: productos:activos (keys de productos activos)
```

### Usuario
```
Key: usuario:{username}
Campos: password, rol
```

## Licencia

Este proyecto es con fines educativos.
