let todos = [];
let filtroActual = 'todos';
let editId = null;
let usuarioActual = null;
let terminoBusqueda = '';
let ordenActual = 'id';

function authHeader() {
  if (!usuarioActual) return {};
  return {'Authorization': 'Bearer ' + usuarioActual.rol + ':' + usuarioActual.username};
}

async function login() {
  const username = document.getElementById('username').value.trim();
  const password = document.getElementById('password').value;
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
    aplicarPermisos(data.rol);
    cargar();
  } else {
    document.getElementById('login-error').style.display = 'block';
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

async function cargar() {
  const r = await fetch('/api/productos');
  todos = await r.json();
  todos.sort((a, b) => parseInt(a.id) - parseInt(b.id));
  aplicarFiltro();
  if (usuarioActual && usuarioActual.rol === 'ADMIN') {
    cargarReportes();
  }
}

async function cargarReportes() {
  const r = await fetch('/api/reportes', {headers: authHeader()});
  if (r.ok) {
    const data = await r.json();
    document.getElementById('rep-total').textContent = data.totalProductos;
    document.getElementById('rep-stock').textContent = data.stockTotal;
    document.getElementById('rep-valor').textContent = '$' + (data.valorTotal / 1000000).toFixed(1) + 'M';
  }
}

function aplicarFiltro() {
  let lista = todos;
  
  if (filtroActual === 'Perifericos') lista = lista.filter(p => p.categoria === 'Perifericos');
  else if (filtroActual === 'Samsung') lista = lista.filter(p => p.marca === 'Samsung');
  else if (filtroActual === 'bajo') lista = lista.filter(p => parseInt(p.stock) < 6);
  
  if (terminoBusqueda) {
    const t = terminoBusqueda.toLowerCase();
    lista = lista.filter(p => p.nombre.toLowerCase().includes(t));
  }
  
  if (ordenActual === 'precio-asc') lista.sort((a, b) => parseInt(a.precio) - parseInt(b.precio));
  else if (ordenActual === 'precio-desc') lista.sort((a, b) => parseInt(b.precio) - parseInt(a.precio));
  else if (ordenActual === 'stock-asc') lista.sort((a, b) => parseInt(a.stock) - parseInt(b.stock));
  else if (ordenActual === 'stock-desc') lista.sort((a, b) => parseInt(b.stock) - parseInt(a.stock));
  else lista.sort((a, b) => parseInt(a.id) - parseInt(b.id));
  
  renderTabla(lista);
}

function filtrar(tipo, btn) {
  filtroActual = tipo;
  document.querySelectorAll('.fbtn').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  aplicarFiltro();
}

function buscar(texto) {
  terminoBusqueda = texto;
  aplicarFiltro();
}

function ordenar(criterio) {
  ordenActual = criterio;
  aplicarFiltro();
}

function renderTabla(lista) {
  const tb = document.getElementById('tabla');
  const puedeEditar = usuarioActual && (usuarioActual.rol === 'ADMIN' || usuarioActual.rol === 'MANAGER');
  const puedeBorrar = usuarioActual && usuarioActual.rol === 'ADMIN';
  
  tb.innerHTML = lista.map(p => `
    <tr>
      <td><div class="pname">${p.nombre}</div><div class="pmarca">${p.marca}</div></td>
      <td><span class="cat">${p.categoria}</span></td>
      <td><span class="price">$${Math.round(p.precio / 1000)}k</span></td>
      <td><span class="${parseInt(p.stock) < 6 ? 'slow' : 'sok'}">${p.stock} u.</span></td>
      <td>
        ${puedeEditar ? `<button class="abtn edit" onclick="abrirEditar('${p.id}')">editar</button>` : ''}
        ${puedeBorrar ? `<button class="abtn del" onclick="eliminar('${p.id}')">borrar</button>` : ''}
      </td>
    </tr>
  `).join('');
  actualizarStats();
}

function actualizarStats() {
  document.getElementById('st-total').textContent = todos.length;
  document.getElementById('st-stock').textContent = todos.reduce((a, p) => a + parseInt(p.stock), 0);
  const avg = todos.length ? todos.reduce((a, p) => a + parseInt(p.precio), 0) / todos.length : 0;
  document.getElementById('st-avg').textContent = '$' + Math.round(avg / 1000) + 'k';
  document.getElementById('st-low').textContent = todos.filter(p => parseInt(p.stock) < 6).length;
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

async function agregarProducto() {
  if (!usuarioActual || usuarioActual.rol !== 'ADMIN') return;
  const nombre = document.getElementById('f-nombre').value.trim();
  const cat = document.getElementById('f-cat').value;
  const marca = document.getElementById('f-marca').value.trim();
  const precio = document.getElementById('f-precio').value;
  const stock = document.getElementById('f-stock').value;
  if (!nombre || !marca || !precio || !stock) { 
    showConfirm('Error', 'Completá todos los campos', function() {});
    return; 
  }
  await fetch('/api/productos', {
    method: 'POST',
    headers: {'Content-Type': 'application/json', ...authHeader()},
    body: JSON.stringify({nombre, categoria: cat, marca, precio, stock})
  });
  log('insert', 'INSERT · ' + nombre);
  document.getElementById('f-nombre').value = '';
  document.getElementById('f-marca').value = '';
  document.getElementById('f-precio').value = '';
  document.getElementById('f-stock').value = '';
  await cargar();
}

function abrirEditar(id) {
  editId = id;
  const p = todos.find(x => x.id == id);
  document.getElementById('m-nombre').value = p.nombre;
  document.getElementById('m-precio').value = p.precio;
  document.getElementById('m-stock').value = p.stock;
  document.getElementById('modal').style.display = 'flex';
}

function cerrarModal() {
  document.getElementById('modal').style.display = 'none';
  editId = null;
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

async function guardarEdicion() {
  const p = todos.find(x => x.id == editId);
  const nombre = document.getElementById('m-nombre').value;
  const precio = document.getElementById('m-precio').value;
  const stock = document.getElementById('m-stock').value;
  
  showConfirm('Guardar cambios', '¿Estás seguro de que deseas guardar los cambios?', async function() {
    await fetch('/api/productos/' + editId, {
      method: 'PUT',
      headers: {'Content-Type': 'application/json', ...authHeader()},
      body: JSON.stringify({nombre, categoria: p.categoria, marca: p.marca, precio, stock})
    });
    log('update', 'UPDATE · ' + nombre);
    cerrarModal();
    await cargar();
  });
}

async function eliminar(id) {
  if (!usuarioActual || usuarioActual.rol !== 'ADMIN') return;
  const p = todos.find(x => x.id == id);
  showConfirm('Eliminar producto', '¿Estás seguro de que deseas eliminar "' + p.nombre + '"?', async function() {
    await fetch('/api/productos/' + id, {method: 'DELETE', headers: authHeader()});
    log('delete', 'DELETE · ' + p.nombre);
    await cargar();
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