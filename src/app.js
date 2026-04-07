let todos = [];
let filtroActual = 'todos';
let editId = null;

async function cargar() {
  const r = await fetch('/api/productos');
  todos = await r.json();
  todos.sort((a, b) => parseInt(a.id) - parseInt(b.id));
  aplicarFiltro();
}

function aplicarFiltro() {
  let lista = todos;
  if (filtroActual === 'Perifericos') lista = todos.filter(p => p.categoria === 'Perifericos');
  else if (filtroActual === 'Samsung') lista = todos.filter(p => p.marca === 'Samsung');
  else if (filtroActual === 'bajo') lista = todos.filter(p => parseInt(p.stock) < 6);
  renderTabla(lista);
}

function filtrar(tipo, btn) {
  filtroActual = tipo;
  document.querySelectorAll('.fbtn').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  aplicarFiltro();
}

function renderTabla(lista) {
  const tb = document.getElementById('tabla');
  tb.innerHTML = lista.map(p => `
    <tr>
      <td><div class="pname">${p.nombre}</div><div class="pmarca">${p.marca}</div></td>
      <td><span class="cat">${p.categoria}</span></td>
      <td><span class="price">$${Math.round(p.precio / 1000)}k</span></td>
      <td><span class="${parseInt(p.stock) < 6 ? 'slow' : 'sok'}">${p.stock} u.</span></td>
      <td>
        <button class="abtn edit" onclick="abrirEditar('${p.id}')">editar</button>
        <button class="abtn del" onclick="eliminar('${p.id}')">borrar</button>
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
  const nombre = document.getElementById('f-nombre').value.trim();
  const cat = document.getElementById('f-cat').value;
  const marca = document.getElementById('f-marca').value.trim();
  const precio = document.getElementById('f-precio').value;
  const stock = document.getElementById('f-stock').value;
  if (!nombre || !marca || !precio || !stock) { alert('Completá todos los campos'); return; }
  await fetch('/api/productos', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({nombre, categoria: cat, marca, precio, stock})
  });
  log('insert', 'INSERT · ' + nombre);
  document.getElementById('f-nombre').value = '';
  document.getElementById('f-marca').value = '';
  document.getElementById('f-precio').value = '';
  document.getElementById('f-stock').value = '';
  await cargar();
}

async function eliminar(id) {
  const p = todos.find(x => x.id == id);
  if (!confirm('¿Eliminar "' + p.nombre + '"?')) return;
  await fetch('/api/productos/' + id, {method: 'DELETE'});
  log('delete', 'DELETE · ' + p.nombre);
  await cargar();
}

function abrirEditar(id) {
  editId = id;
  const p = todos.find(x => x.id == id);
  document.getElementById('m-nombre').value = p.nombre;
  document.getElementById('m-precio').value = p.precio;
  document.getElementById('m-stock').value = p.stock;
  document.getElementById('modal').classList.add('open');
}

function cerrarModal() {
  document.getElementById('modal').classList.remove('open');
  editId = null;
}

async function guardarEdicion() {
  const p = todos.find(x => x.id == editId);
  const nombre = document.getElementById('m-nombre').value;
  const precio = document.getElementById('m-precio').value;
  const stock = document.getElementById('m-stock').value;
  await fetch('/api/productos/' + editId, {
    method: 'PUT',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({nombre, categoria: p.categoria, marca: p.marca, precio, stock})
  });
  log('update', 'UPDATE · ' + nombre);
  cerrarModal();
  await cargar();
}

cargar();