let todos = [];
let filtroActual = 'todos';
let editId = null;
let usuarioActual = null;
let terminoBusqueda = '';
let ordenActual = 'id';
let loginEnProgreso = false; // guard contra doble submit
let categoriaSeleccionada = '';

/** Devuelve true si un producto está activo. Centraliza la comparación para evitar duplicación. */
function esActivo(p)  { return p.activo === 'true'  || p.activo === true; }
function esInactivo(p){ return p.activo === 'false' || p.activo === false; }

/**
 * Muestra un error inline bajo un campo de formulario (sin modal, sin alert).
 * @param {string} fieldId - id del input
 * @param {string} msg     - mensaje a mostrar ('' para limpiar)
 */
function showFieldError(fieldId, msg) {
  const el = document.getElementById(fieldId);
  if (!el) return;
  el.style.borderColor = msg ? '#ef4444' : '';
  let hint = el.parentElement.querySelector('.field-hint');
  if (!hint) {
    hint = document.createElement('div');
    hint.className = 'field-hint';
    hint.style.cssText = 'font-size:11px;color:#ef4444;margin-top:-8px;margin-bottom:6px;font-family:monospace';
    el.insertAdjacentElement('afterend', hint);
  }
  hint.textContent = msg;
}
function clearFieldErrors(...ids) {
  ids.forEach(id => showFieldError(id, ''));
}

function authHeader() {
  if (!usuarioActual) return {};
  return {'Authorization': 'Bearer ' + usuarioActual.rol + ':' + usuarioActual.username};
}

/** Intenta autenticar al usuario. Previene doble submit. */
async function login() {
  if (loginEnProgreso) return;
  const username = document.getElementById('username').value.trim();
  const password = document.getElementById('password').value;
  if (!username || !password) {
    document.getElementById('login-error').textContent = 'Completá usuario y contraseña.';
    document.getElementById('login-error').style.display = 'block';
    return;
  }
  loginEnProgreso = true;
  const loginBtn = document.querySelector('.login-btn');
  loginBtn.textContent = 'Ingresando...';
  loginBtn.disabled = true;
  try {
    const res = await fetch('/api/login', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({username, password})
    });
    const data = await res.json();
    if (data.ok) {
      usuarioActual = {username: data.username, rol: data.rol};
      document.getElementById('login-page').style.display = 'none';
      document.getElementById('app').style.display = 'block';
      document.getElementById('user-name').textContent = data.username;
      document.getElementById('user-role').textContent = data.rol;
      document.getElementById('login-error').style.display = 'none';
      aplicarPermisos(data.rol);
      cargar();
    } else {
      document.getElementById('login-error').textContent = data.error || 'Credenciales inválidas';
      document.getElementById('login-error').style.display = 'block';
    }
  } catch (e) {
    document.getElementById('login-error').textContent = 'Error de red. Verificá la conexión.';
    document.getElementById('login-error').style.display = 'block';
  } finally {
    loginEnProgreso = false;
    loginBtn.textContent = 'Iniciar sesión';
    loginBtn.disabled = false;
  }
}

function logout() {
  usuarioActual = null;
  document.getElementById('app').style.display = 'none';
  document.getElementById('login-page').style.display = 'flex';
  document.getElementById('username').value = '';
  document.getElementById('password').value = '';
  document.getElementById('login-error').style.display = 'none';
}

function aplicarPermisos(rol) {
  const agregarCard = document.getElementById('agregar-card');
  const reportesCard = document.getElementById('reportes-card');
  
  if (rol === 'ADMIN') {
    agregarCard.style.display = 'block';
    reportesCard.style.display = 'block';
  } else if (rol === 'MANAGER') {
    agregarCard.style.display = 'block';
    reportesCard.style.display = 'none';
  } else {
    agregarCard.style.display = 'none';
    reportesCard.style.display = 'none';
  }
}

/** Carga todos los productos (activos + discontinuados) desde la API. */
async function cargar() {
  const filtroGuardado = filtroActual;
  const catGuardada = categoriaSeleccionada;
  todos = [];
  try {
    const r1 = await fetch('/api/productos', {headers: {'X-Filtro-Activo': 'true'}});
    const r2 = await fetch('/api/productos', {headers: {'X-Filtro-Activo': 'false'}});
    if (!r1.ok || !r2.ok) throw new Error('Respuesta inesperada del servidor');
    const activos       = await r1.json();
    const discontinuados = await r2.json();
    todos = [
      ...(Array.isArray(activos)       ? activos       : []),
      ...(Array.isArray(discontinuados) ? discontinuados : [])
    ];
    todos.sort((a, b) => parseInt(a.id) - parseInt(b.id));
    
    filtroActual = filtroGuardado;
    categoriaSeleccionada = catGuardada;
    actualizarCategorias();
    aplicarFiltro();
    cargarLogs();
    if (usuarioActual && usuarioActual.rol === 'ADMIN') cargarReportes();
  } catch (e) {
    console.error('Error de red al cargar productos:', e);
    showConfirm('Sin conexión', 'No se pudo conectar con el servidor. Reintentá en unos segundos.', function() {});
  }
}

/** Carga y muestra el panel de reportes (solo ADMIN). */
async function cargarReportes() {
  try {
    const r = await fetch('/api/reportes', {headers: authHeader()});
    if (r.ok) {
      const data = await r.json();
      document.getElementById('rep-activos').textContent  = data.activos;
      document.getElementById('rep-disc').textContent     = data.discontinuados;
      document.getElementById('rep-stock').textContent    = data.stockTotal;
document.getElementById('rep-valor').textContent = '$' + data.valorTotal.toLocaleString('es-AR');
      document.getElementById('rep-stock-bajo').textContent = data.stockBajo;
      document.getElementById('rep-categorias').textContent =
        Object.entries(data.categorias || {}).map(([cat, n]) => cat + ': ' + n).join(' · ');
    } else if (r.status === 403) {
      console.warn('Sin permiso para ver reportes.');
    }
  } catch (e) {
    console.error('Error de red al cargar reportes:', e);
  }
}

/**
 * Carga los logs de auditoría desde Redis (/api/logs) y actualiza
 * el panel "Actividad reciente" (#ops) con los datos reales del servidor.
 * Solo disponible para ADMIN y MANAGER (el backend devuelve 401/403 si no).
 * Cada entrada del servidor es un JSON object: {accion, detalle, usuario, timestamp}
 */
async function cargarLogs() {
  // Solo ADMIN y MANAGER pueden ver logs — no hacer fetch para EMPLOYEE
  if (!usuarioActual || usuarioActual.rol === 'EMPLOYEE') return;
  try {
    const r = await fetch('/api/logs', {headers: authHeader()});
    if (r.status === 401 || r.status === 403) return; // silencioso: sin permiso
    if (!r.ok) return;
    const logs = await r.json();
    if (!Array.isArray(logs) || logs.length === 0) return;
    // Mapa de acción a clase CSS del punto de color
    const colorMap = { CREATE: 'insert', UPDATE: 'update', DELETE: 'delete' };
    const ops = document.getElementById('ops');
    ops.innerHTML = logs.map(entry => {
      // Cada entry es un objeto JSON {accion, detalle, usuario, timestamp}
      let accion = '?', detalle = '?', usuario = '?', hora = '';
      try {
        // entry puede venir ya parseado (objeto) o como string JSON
        const obj = typeof entry === 'string' ? JSON.parse(entry) : entry;
        accion  = obj.accion    || '?';
        detalle = obj.detalle   || '?';
        usuario = obj.usuario   || '?';
        hora    = obj.timestamp || '';
      } catch (_) { /* entry malformado: mostrar igualmente con defaults */ }
      const tipo  = colorMap[accion] || 'read';
      const texto = accion + ' · ' + detalle + ' (' + usuario + ')';
      return `<div class="op"><div class="odot ${tipo}"></div><span class="otext">${texto}</span><span class="otime">${hora}</span></div>`;
    }).join('');
  } catch (e) {
    console.error('Error al cargar logs:', e);
  }
}

/** Aplica el filtro activo + búsqueda + orden y re-renderiza la tabla. */
function aplicarFiltro() {
  let lista = todos;
  
  if      (filtroActual === 'todos')         lista = lista.filter(esActivo);
  else if (filtroActual === 'bajo')          lista = lista.filter(p => parseInt(p.stock) < 6 && esActivo(p));
  else if (filtroActual === 'discontinuados') lista = lista.filter(esInactivo);
  
  if (categoriaSeleccionada) {
    lista = lista.filter(p => p.categoria === categoriaSeleccionada && esActivo(p));
  }
  
  if (terminoBusqueda) {
    const t = terminoBusqueda.toLowerCase();
    lista = lista.filter(p => p.nombre.toLowerCase().includes(t) || p.marca.toLowerCase().includes(t));
  }
  
  if      (ordenActual === 'precio-asc')  lista.sort((a, b) => parseInt(a.precio) - parseInt(b.precio));
  else if (ordenActual === 'precio-desc') lista.sort((a, b) => parseInt(b.precio) - parseInt(a.precio));
  else if (ordenActual === 'stock-asc')   lista.sort((a, b) => parseInt(a.stock)  - parseInt(b.stock));
  else if (ordenActual === 'stock-desc')  lista.sort((a, b) => parseInt(b.stock)  - parseInt(a.stock));
  else lista.sort((a, b) => a.nombre.localeCompare(b.nombre));
  
  renderTabla(lista);
}

function filtrar(tipo, btn) {
  filtroActual = tipo;
  if (tipo !== 'categoria') {
    categoriaSeleccionada = '';
    document.getElementById('filtro-cat').value = '';
  }
  document.querySelectorAll('.fbtn').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  aplicarFiltro();
}

function filtrarCategoria(cat) {
  categoriaSeleccionada = cat;
  document.querySelectorAll('.fbtn').forEach(b => b.classList.remove('active'));
  if (cat) {
    document.getElementById('btn-todos').classList.add('active');
  } else {
    document.getElementById('btn-todos').classList.add('active');
    filtroActual = 'todos';
  }
  aplicarFiltro();
}

function actualizarCategorias() {
  const select = document.getElementById('filtro-cat');
  const cats = [...new Set(todos.filter(p => esActivo(p)).map(p => p.categoria))].sort();
  select.innerHTML = '<option value="">Categoría</option>';
  cats.forEach(cat => {
    const opt = document.createElement('option');
    opt.value = cat;
    opt.textContent = cat;
    select.appendChild(opt);
  });
}

function buscar(texto) {
  terminoBusqueda = texto;
  aplicarFiltro();
}

function ordenar(criterio) {
  ordenActual = criterio;
  aplicarFiltro();
}

/** Renderiza la tabla de productos con los permisos del usuario actual. */
function renderTabla(lista) {
  const tb = document.getElementById('tabla');
  const puedeEditar = usuarioActual && (usuarioActual.rol === 'ADMIN' || usuarioActual.rol === 'MANAGER');
  const puedeBorrar = usuarioActual && usuarioActual.rol === 'ADMIN';
  
  if (lista.length === 0) {
    tb.innerHTML = `<tr><td colspan="5" style="text-align:center;color:#aaa;padding:2rem;font-size:13px">Sin resultados para el filtro seleccionado</td></tr>`;
    actualizarStats();
    return;
  }

  tb.innerHTML = lista.map(p => `
    <tr style="${esInactivo(p) ? 'opacity:0.5' : ''}">
      <td><div class="pname">${p.nombre}</div><div class="pmarca">${p.marca}</div></td>
      <td><span class="cat">${p.categoria}</span></td>
      <td><span class="price">$${Math.round((parseInt(p.precio) || 0) / 1000)}k</span></td>
      <td><span class="${parseInt(p.stock) < 6 ? 'slow' : 'sok'}">${p.stock} u.</span></td>
      <td>
        ${esActivo(p) && puedeEditar ? `<button class="abtn edit" onclick="abrirEditar('${p.id}')">editar</button>` : ''}
        ${esActivo(p) && puedeBorrar ? `<button class="abtn del" onclick="eliminar('${p.id}')">borrar</button>` : ''}
        ${esInactivo(p) && puedeBorrar ? `<button class="abtn" onclick="abrirReactivar('${p.id}')" style="color:#22c55e">reactivar</button>` : ''}
        ${esInactivo(p) && !puedeBorrar ? `<span style="font-size:11px;color:#888">discontinuado</span>` : ''}
      </td>
    </tr>
  `).join('');
  actualizarStats();
}

/** Actualiza los contadores del dashboard usando los datos en memoria. Usa ||0 para evitar NaN. */
function actualizarStats() {
  const activos       = todos.filter(esActivo);
  const discontinuados = todos.filter(esInactivo);
  
  document.getElementById('st-activos').textContent  = activos.length;
  document.getElementById('st-disc-sub').textContent = discontinuados.length + ' discontinuado';
  document.getElementById('st-stock').textContent    = activos.reduce((a, p) => a + (parseInt(p.stock)  || 0), 0);
  const avg = activos.length ? activos.reduce((a, p) => a + (parseInt(p.precio) || 0), 0) / activos.length : 0;
  document.getElementById('st-avg').textContent      = '$' + Math.round(avg / 1000) + 'k';
  document.getElementById('st-low').textContent      = activos.filter(p => (parseInt(p.stock) || 0) < 6).length;
}

function log(tipo, texto) {
  const ops = document.getElementById('ops');
  const now = new Date().toLocaleTimeString('es-AR', {hour: '2-digit', minute: '2-digit'});
  const d = document.createElement('div');
  d.className = 'op';
  d.innerHTML = `<div class="odot ${tipo}"></div><span class="otext">${texto}</span><span class="otime">${now}</span>`;
  ops.insertBefore(d, ops.firstChild);
  if (ops.children.length > 6) ops.removeChild(ops.lastChild);
}

/** Agrega un producto nuevo. Valida campos antes de enviar al backend. */
async function agregarProducto() {
  if (!usuarioActual || (usuarioActual.rol !== 'ADMIN' && usuarioActual.rol !== 'MANAGER')) return;
  const nombre = document.getElementById('f-nombre').value.trim();
  const cat    = document.getElementById('f-cat').value;
  const marca  = document.getElementById('f-marca').value.trim();
  const precio = document.getElementById('f-precio').value;
  const stock  = document.getElementById('f-stock').value;
  // Limpiar errores previos
  clearFieldErrors('f-nombre', 'f-marca', 'f-precio', 'f-stock');
  let hasError = false;
  if (!nombre) { showFieldError('f-nombre', 'Requerido'); hasError = true; }
  if (!marca)  { showFieldError('f-marca',  'Requerido'); hasError = true; }
  if (!precio || isNaN(Number(precio))) { showFieldError('f-precio', 'Debe ser un número'); hasError = true; }
  else if (Number(precio) <= 0)         { showFieldError('f-precio', 'Debe ser mayor a 0'); hasError = true; }
  if (!stock || isNaN(Number(stock)))   { showFieldError('f-stock',  'Debe ser un número'); hasError = true; }
  else if (Number(stock) < 0)           { showFieldError('f-stock',  'No puede ser negativo'); hasError = true; }
  if (hasError) return;
  const btn = document.querySelector('#agregar-card .btn-primary');
  btn.textContent = 'Guardando...';
  btn.disabled = true;
  try {
    const res = await fetch('/api/productos', {
      method: 'POST',
      headers: {'Content-Type': 'application/json', ...authHeader()},
      body: JSON.stringify({nombre, categoria: cat, marca, precio, stock})
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      showConfirm('Error al agregar', err.error || 'No se pudo agregar el producto', function() {}); return;
    }
    log('insert', 'INSERT · ' + nombre);
    document.getElementById('f-nombre').value = '';
    document.getElementById('f-marca').value  = '';
    document.getElementById('f-precio').value = '';
    document.getElementById('f-stock').value  = '';
    await cargar();
  } catch (e) {
    showConfirm('Error de red', 'No se pudo conectar con el servidor', function() {});
  } finally {
    btn.textContent = 'Agregar producto';
    btn.disabled = false;
  }
}

/** Abre el modal de edición con los datos del producto. */
function abrirEditar(id) {
  const p = todos.find(x => x.id == id);
  if (!p) { console.warn('abrirEditar: producto no encontrado en memoria, id=', id); return; }
  editId = id;
  document.getElementById('m-nombre').value = p.nombre;
  document.getElementById('m-cat').value    = p.categoria;
  document.getElementById('m-marca').value  = p.marca;
  document.getElementById('m-precio').value = p.precio;
  document.getElementById('m-stock').value  = p.stock;
  document.getElementById('modal').style.display = 'flex';
}

function cerrarModal() {
  document.getElementById('modal').style.display = 'none';
  clearFieldErrors('m-nombre', 'm-marca', 'm-precio', 'm-stock');
  editId = null;
}

let reactivarId = null;

function abrirReactivar(id) {
  reactivarId = id;
  const p = todos.find(x => x.id == id);
  document.getElementById('reactivar-nombre').textContent = p.nombre + ' - ' + p.marca;
  document.getElementById('reactivar-stock').value = '';
  document.getElementById('reactivar-overlay').style.display = 'flex';
}

function cerrarReactivar() {
  document.getElementById('reactivar-overlay').style.display = 'none';
  reactivarId = null;
}

async function confirmarReactivar() {
  const stock = document.getElementById('reactivar-stock').value;
  if (!stock || parseInt(stock) < 1) {
    alert('Ingresá un stock válido (mayor a 0)');
    return;
  }
  
  await fetch('/api/reactivar/' + reactivarId, {
    method: 'POST',
    headers: {'Content-Type': 'application/json', ...authHeader()},
    body: JSON.stringify({stock: stock})
  });
  
  cerrarReactivar();
  await cargar();
}

function showConfirm(titulo, mensaje, onConfirm) {
  document.getElementById('confirm-titulo').textContent = titulo;
  document.getElementById('confirm-mensaje').textContent = mensaje;
  document.getElementById('confirm-overlay').style.display = 'flex';
  
  document.getElementById('confirm-ok').onclick = function() {
    document.getElementById('confirm-overlay').style.display = 'none';
    onConfirm();
  };
  document.getElementById('confirm-cancel').onclick = function() {
    document.getElementById('confirm-overlay').style.display = 'none';
  };
}

/** Guarda los cambios de edición de un producto con confirmación previa. */
async function guardarEdicion() {
  if (!editId) return; // guard: no hay edición activa
  const nombre    = document.getElementById('m-nombre').value.trim();
  const categoria = document.getElementById('m-cat').value;
  const marca     = document.getElementById('m-marca').value.trim();
  const precio    = document.getElementById('m-precio').value;
  const stock     = document.getElementById('m-stock').value;
  if (!nombre || !marca) {
    showConfirm('Error', 'Nombre y marca son obligatorios', function() {}); return;
  }
  if (Number(precio) <= 0) {
    showConfirm('Error', 'El precio debe ser mayor a 0', function() {}); return;
  }
  if (Number(stock) < 0) {
    showConfirm('Error', 'El stock no puede ser negativo', function() {}); return;
  }
  // Deshabilitar el botón Guardar del modal para toda la operación (confirm + fetch)
  const guardarBtn = document.querySelector('#modal .btn-primary');
  if (guardarBtn) { guardarBtn.textContent = 'Guardando...'; guardarBtn.disabled = true; }
  const idParaEditar = editId; // captura local antes del await (guard anti-race)
  showConfirm('¿Guardar cambios?', '¿Estás seguro de que deseas guardar los cambios?', async function() {
    const saveBtn = document.getElementById('confirm-ok');
    saveBtn.textContent = 'Guardando...';
    saveBtn.disabled = true;
    try {
      const res = await fetch('/api/productos/' + idParaEditar, {
        method: 'PUT',
        headers: {'Content-Type': 'application/json', ...authHeader()},
        body: JSON.stringify({nombre, categoria, marca, precio, stock})
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        showConfirm('Error al guardar', err.error || 'No se pudo guardar', function() {});
        return;
      }
      log('update', 'UPDATE · ' + nombre);
      cerrarModal();
      await cargar();
    } catch (e) {
      showConfirm('Error de red', 'No se pudo guardar los cambios.', function() {});
    } finally {
      saveBtn.textContent = 'Aceptar';
      saveBtn.disabled = false;
      // Restaurar botón Guardar del modal (en caso de error — si ok, cerrarModal ya lo ocultó)
      if (guardarBtn) { guardarBtn.textContent = 'Guardar'; guardarBtn.disabled = false; }
    }
  });
}

/** Marca un producto como discontinuado (borrado lógico) con confirmación previa. */
async function eliminar(id) {
  if (!usuarioActual || usuarioActual.rol !== 'ADMIN') return;
  const p = todos.find(x => x.id == id);
  if (!p) { console.warn('eliminar: producto no encontrado en memoria, id=', id); return; }
  showConfirm('Eliminar producto', '¿Estás seguro de que deseas eliminar "' + p.nombre + '"?', async function() {
    try {
      const res = await fetch('/api/productos/' + id, {method: 'DELETE', headers: authHeader()});
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        showConfirm('Error', err.error || 'No se pudo eliminar el producto.', function() {}); return;
      }
      log('delete', 'DELETE · ' + p.nombre);
      await cargar();
    } catch (e) {
      showConfirm('Error de red', 'No se pudo eliminar el producto.', function() {});
    }
  });
}

document.addEventListener('DOMContentLoaded', function() {
  document.getElementById('password').addEventListener('keypress', function(e) {
    if (e.key === 'Enter') login();
  });
  document.getElementById('busqueda').addEventListener('input', function(e) {
    buscar(e.target.value);
  });
});