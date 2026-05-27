let ws = null;
let riskChart = null;
let mapCanvas = null;
let mapCtx = null;

function init() {
    setupNavigation();
    setupWebSocket();
    loadDashboard();
    setupCommandPalette();
    setupQuickActions();
    setupNotifications();
    setupMap();
}

function setupNavigation() {
    const navItems = document.querySelectorAll('.nav-item');
    navItems.forEach(item => {
        item.addEventListener('click', (e) => {
            e.preventDefault();
            const page = item.dataset.page;
            switchPage(page);
            
            navItems.forEach(nav => nav.classList.remove('active'));
            item.classList.add('active');
        });
    });
}

function switchPage(page) {
    const pages = document.querySelectorAll('.page');
    pages.forEach(p => p.classList.remove('active'));
    
    const targetPage = document.getElementById(`page-${page}`);
    if (targetPage) {
        targetPage.classList.add('active');
        
        if (page === 'players') {
            loadPlayers();
        } else if (page === 'config') {
            loadConfig();
        }
    }
}

function setupWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws`;
    
    try {
        ws = new WebSocket(wsUrl);
        
        ws.onopen = () => {
            console.log('WebSocket connected');
            updateConnectionStatus(true);
        };
        
        ws.onmessage = (event) => {
            handleWebSocketMessage(event.data);
        };
        
        ws.onclose = () => {
            console.log('WebSocket disconnected');
            updateConnectionStatus(false);
            setTimeout(setupWebSocket, 5000);
        };
        
        ws.onerror = (error) => {
            console.error('WebSocket error:', error);
        };
    } catch (e) {
        console.error('WebSocket setup failed:', e);
        setTimeout(setupWebSocket, 5000);
    }
}

function updateConnectionStatus(connected) {
    const statusDot = document.querySelector('.status-dot');
    if (connected) {
        statusDot.classList.add('connected');
    } else {
        statusDot.classList.remove('connected');
    }
}

function handleWebSocketMessage(data) {
    try {
        const message = JSON.parse(data);
        
        if (message.type === 'event') {
            addEventToStream(message.data);
        } else if (message.type === 'update') {
            updateDashboard(message.data);
        }
    } catch (e) {
        console.error('Failed to parse WebSocket message:', e);
    }
}

async function loadDashboard() {
    try {
        const response = await fetch('/api/dashboard');
        const data = await response.json();
        updateDashboard(data);
        loadEvents();
        setupRiskChart(data.riskTrend);
    } catch (e) {
        console.error('Failed to load dashboard:', e);
    }
}

function updateDashboard(data) {
    document.getElementById('onlinePlayers').textContent = data.onlinePlayers || 0;
    document.getElementById('suspectPlayers').textContent = data.suspectPlayers || 0;
    document.getElementById('todayIntercepts').textContent = data.todayIntercepts || 0;
    document.getElementById('captchaRate').textContent = Math.round((data.captchaSuccessRate || 0.85) * 100) + '%';
    document.getElementById('activeCases').textContent = data.activeCases || 3;
    
    updateRiskIndicator(data.riskLevel || 65);
}

function updateRiskIndicator(level) {
    const dot = document.getElementById('riskDot');
    const levelName = document.getElementById('riskLevelName');
    const riskValue = document.getElementById('riskValue');
    
    riskValue.textContent = level + '%';
    
    if (level < 30) {
        dot.style.background = 'var(--success-color)';
        levelName.textContent = '低风险';
    } else if (level < 60) {
        dot.style.background = 'var(--accent-yellow)';
        levelName.textContent = '中风险';
    } else if (level < 80) {
        dot.style.background = 'var(--accent-orange)';
        levelName.textContent = '高风险';
    } else {
        dot.style.background = 'var(--accent-red)';
        levelName.textContent = '严重风险';
    }
}

function setupRiskChart(data) {
    const ctx = document.getElementById('riskChart').getContext('2d');
    
    if (riskChart) {
        riskChart.destroy();
    }
    
    riskChart = new Chart(ctx, {
        type: 'line',
        data: {
            labels: ['00:00', '04:00', '08:00', '12:00', '16:00', '20:00', '现在'],
            datasets: [{
                label: '风险评分',
                data: data || [65, 58, 72, 81, 69, 75, 70],
                borderColor: '#00e5ff',
                backgroundColor: 'rgba(0, 229, 255, 0.1)',
                tension: 0.4,
                fill: true
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {
                    display: false
                }
            },
            scales: {
                y: {
                    min: 0,
                    max: 100,
                    grid: {
                        color: 'rgba(58, 68, 88, 0.5)'
                    },
                    ticks: {
                        color: '#a0aec0'
                    }
                },
                x: {
                    grid: {
                        color: 'rgba(58, 68, 88, 0.5)'
                    },
                    ticks: {
                        color: '#a0aec0'
                    }
                }
            }
        }
    });
}

async function loadEvents() {
    try {
        const response = await fetch('/api/events');
        const events = await response.json();
        
        const stream = document.getElementById('eventStream');
        stream.innerHTML = '';
        
        events.forEach(event => {
            addEventToStream(event);
        });
    } catch (e) {
        console.error('Failed to load events:', e);
    }
}

function addEventToStream(event) {
    const stream = document.getElementById('eventStream');
    const eventEl = document.createElement('div');
    eventEl.className = 'event-item';
    
    const time = event.time || new Date().toLocaleTimeString();
    const typeClass = event.type || 'info';
    const player = event.player || 'System';
    const module = event.module || 'General';
    const score = event.score || 0;
    
    eventEl.innerHTML = `
        <span class="event-time">[${time}]</span>
        <span class="event-type ${typeClass}">${getTypeLabel(typeClass)}</span>
        <span>${player} - ${module} - 评分: ${score}</span>
    `;
    
    stream.insertBefore(eventEl, stream.firstChild);
    
    while (stream.children.length > 50) {
        stream.removeChild(stream.lastChild);
    }
}

function getTypeLabel(type) {
    const labels = {
        'high': '严重',
        'warning': '警告',
        'success': '正常',
        'info': '信息'
    };
    return labels[type] || '信息';
}

async function loadPlayers() {
    try {
        const response = await fetch('/api/players');
        const players = await response.json();
        
        const tbody = document.getElementById('playersTableBody');
        tbody.innerHTML = '';
        
        players.forEach(player => {
            const tr = document.createElement('tr');
            const riskLevel = getRiskLevel(player.riskScore);
            
            tr.innerHTML = `
                <td><span class="status-dot-player ${riskLevel}"></span></td>
                <td><strong>${player.name}</strong></td>
                <td>${player.ping}ms</td>
                <td>${player.client}</td>
                <td>
                    <div class="risk-bar">
                        <div class="risk-bar-fill ${riskLevel}" style="width: ${player.riskScore}%"></div>
                    </div>
                    <span>${player.riskScore}%</span>
                </td>
                <td>${player.suspectedReason || '-'}</td>
                <td>${player.onlineTime}</td>
                <td>
                    <div class="action-buttons">
                        <button class="action-btn view">查看</button>
                        <button class="action-btn warn">警告</button>
                        <button class="action-btn ban">封禁</button>
                    </div>
                </td>
            `;
            
            tbody.appendChild(tr);
        });
    } catch (e) {
        console.error('Failed to load players:', e);
    }
}

function getRiskLevel(score) {
    if (score < 30) return 'low';
    if (score < 60) return 'medium';
    return 'high';
}

async function loadConfig() {
    try {
        const response = await fetch('/api/config');
        const config = await response.json();
        
        const grid = document.getElementById('configGrid');
        grid.innerHTML = '';
        
        const modules = {
            'fly': '飞行检测',
            'speed': '速度检测',
            'killAura': '杀戮光环',
            'reach': '距离检测',
            'esp': '透视检测',
            'fastBreak': '快速挖掘',
            'scaffold': '脚手架检测',
            'noSlow': '无减速检测'
        };
        
        Object.entries(config.modules || {}).forEach(([key, module]) => {
            const card = document.createElement('div');
            card.className = 'config-card';
            
            card.innerHTML = `
                <div class="config-header">
                    <span class="config-name">${modules[key] || key}</span>
                    <label class="config-toggle">
                        <input type="checkbox" ${module.enabled ? 'checked' : ''}>
                        <span class="slider"></span>
                    </label>
                </div>
                <div class="config-slider">
                    <input type="range" min="1" max="10" value="${module.sensitivity || 5}">
                    <div class="slider-value">灵敏度: ${module.sensitivity || 5}</div>
                </div>
            `;
            
            grid.appendChild(card);
        });
        
        document.querySelectorAll('.config-slider input[type="range"]').forEach(input => {
            input.addEventListener('input', (e) => {
                e.target.parentElement.querySelector('.slider-value').textContent = `灵敏度: ${e.target.value}`;
            });
        });
    } catch (e) {
        console.error('Failed to load config:', e);
    }
}

function setupMap() {
    mapCanvas = document.getElementById('mapCanvas');
    if (!mapCanvas) return;
    
    mapCtx = mapCanvas.getContext('2d');
    resizeMap();
    
    window.addEventListener('resize', resizeMap);
    drawMap();
}

function resizeMap() {
    if (!mapCanvas) return;
    
    const container = mapCanvas.parentElement;
    mapCanvas.width = container.clientWidth - 40;
    mapCanvas.height = container.clientHeight - 40;
    
    drawMap();
}

function drawMap() {
    if (!mapCtx || !mapCanvas) return;
    
    const width = mapCanvas.width;
    const height = mapCanvas.height;
    
    mapCtx.fillStyle = '#0a0e17';
    mapCtx.fillRect(0, 0, width, height);
    
    mapCtx.strokeStyle = '#1a1e2b';
    mapCtx.lineWidth = 1;
    
    for (let x = 0; x < width; x += 40) {
        mapCtx.beginPath();
        mapCtx.moveTo(x, 0);
        mapCtx.lineTo(x, height);
        mapCtx.stroke();
    }
    
    for (let y = 0; y < height; y += 40) {
        mapCtx.beginPath();
        mapCtx.moveTo(0, y);
        mapCtx.lineTo(width, y);
        mapCtx.stroke();
    }
    
    const players = [
        { x: 0.3, y: 0.4, risk: 15 },
        { x: 0.5, y: 0.6, risk: 45 },
        { x: 0.7, y: 0.3, risk: 85 },
        { x: 0.2, y: 0.7, risk: 25 }
    ];
    
    players.forEach(player => {
        const px = player.x * width;
        const py = player.y * height;
        
        let color;
        if (player.risk < 30) color = '#22c55e';
        else if (player.risk < 60) color = '#eab308';
        else color = '#ef4444';
        
        mapCtx.beginPath();
        mapCtx.arc(px, py, 8, 0, Math.PI * 2);
        mapCtx.fillStyle = color;
        mapCtx.fill();
        
        mapCtx.beginPath();
        mapCtx.arc(px, py, 12, 0, Math.PI * 2);
        mapCtx.strokeStyle = color;
        mapCtx.globalAlpha = 0.3;
        mapCtx.lineWidth = 2;
        mapCtx.stroke();
        mapCtx.globalAlpha = 1;
    });
}

function setupCommandPalette() {
    const palette = document.getElementById('commandPalette');
    const input = palette.querySelector('.command-input');
    
    document.addEventListener('keydown', (e) => {
        if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
            e.preventDefault();
            openCommandPalette();
        }
        if (e.key === 'Escape') {
            closeCommandPalette();
        }
    });
    
    palette.addEventListener('click', (e) => {
        if (e.target === palette) {
            closeCommandPalette();
        }
    });
}

function openCommandPalette() {
    const palette = document.getElementById('commandPalette');
    palette.classList.add('show');
    palette.querySelector('.command-input').focus();
}

function closeCommandPalette() {
    const palette = document.getElementById('commandPalette');
    palette.classList.remove('show');
}

function setupQuickActions() {
    const toggle = document.getElementById('quickActionsToggle');
    const menu = document.getElementById('quickActionsMenu');
    
    toggle.addEventListener('click', () => {
        toggle.classList.toggle('open');
        menu.classList.toggle('show');
    });
}

function setupNotifications() {
    const btn = document.getElementById('notificationBtn');
    const dropdown = document.getElementById('notificationDropdown');
    
    btn.addEventListener('click', (e) => {
        e.stopPropagation();
        dropdown.classList.toggle('show');
    });
    
    document.addEventListener('click', () => {
        dropdown.classList.remove('show');
    });
}

document.addEventListener('DOMContentLoaded', init);
