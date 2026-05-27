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
        } else if (page === 'map') {
            drawMap();
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
        } else if (message.type === 'playerUpdate') {
            if (riskChart) {
                riskChart.data.datasets[0].data = message.data.riskTrend || [];
                riskChart.update();
            }
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
    document.getElementById('captchaRate').textContent = Math.round((data.captchaSuccessRate || 0) * 100) + '%';
    document.getElementById('activeCases').textContent = data.activeCases || 0;
    
    updateRiskIndicator(data.riskLevel || 0);
}

function updateRiskIndicator(level) {
    const dot = document.getElementById('riskDot');
    const levelName = document.getElementById('riskLevelName');
    const riskValue = document.getElementById('riskValue');
    
    riskValue.textContent = level + '%';
    
    if (level < 30) {
        dot.style.background = '#22c55e';
        levelName.textContent = '低风险';
    } else if (level < 60) {
        dot.style.background = '#eab308';
        levelName.textContent = '中风险';
    } else if (level < 80) {
        dot.style.background = '#ff6d00';
        levelName.textContent = '高风险';
    } else {
        dot.style.background = '#ef4444';
        levelName.textContent = '严重风险';
    }
}

function setupRiskChart(data) {
    const ctx = document.getElementById('riskChart');
    if (!ctx) return;
    
    const chartCtx = ctx.getContext('2d');
    
    if (riskChart) {
        riskChart.destroy();
    }
    
    const labels = data && data.length > 0 ? generateTimeLabels(data.length) : ['暂无数据'];
    
    riskChart = new Chart(chartCtx, {
        type: 'line',
        data: {
            labels: labels,
            datasets: [{
                label: '风险评分',
                data: data || [],
                borderColor: '#00e5ff',
                backgroundColor: 'rgba(0, 229, 255, 0.1)',
                tension: 0.4,
                fill: true,
                pointRadius: 3,
                pointHoverRadius: 6
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {
                    display: false
                },
                tooltip: {
                    mode: 'index',
                    intersect: false
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
                        color: '#a0aec0',
                        maxRotation: 0
                    }
                }
            }
        }
    });
}

function generateTimeLabels(count) {
    const labels = [];
    const now = new Date();
    for (let i = Math.max(0, count - 1); i >= 0; i--) {
        const time = new Date(now - i * 3600000);
        labels.push(time.getHours().toString().padStart(2, '0') + ':00');
    }
    return labels;
}

async function loadEvents() {
    try {
        const response = await fetch('/api/events');
        const events = await response.json();
        
        const stream = document.getElementById('eventStream');
        if (!stream) return;
        
        stream.innerHTML = '<div style="color: #666; text-align: center; padding: 20px;">暂无事件记录</div>';
        
        if (events && events.length > 0) {
            stream.innerHTML = '';
            events.forEach(event => {
                addEventToStream(event);
            });
        }
    } catch (e) {
        console.error('Failed to load events:', e);
    }
}

function addEventToStream(event) {
    const stream = document.getElementById('eventStream');
    if (!stream) return;
    
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
        if (!tbody) return;
        
        tbody.innerHTML = '';
        
        if (!players || players.length === 0) {
            tbody.innerHTML = '<tr><td colspan="8" style="text-align: center; color: #666;">暂无在线玩家</td></tr>';
            return;
        }
        
        players.forEach(player => {
            const tr = document.createElement('tr');
            const riskLevel = getRiskLevel(player.riskScore);
            
            tr.innerHTML = `
                <td><span class="status-dot-player ${riskLevel}"></span></td>
                <td><strong>${player.name}</strong></td>
                <td>${player.ping || 0}ms</td>
                <td>${player.client || 'Unknown'}</td>
                <td>
                    <div class="risk-bar">
                        <div class="risk-bar-fill ${riskLevel}" style="width: ${player.riskScore || 0}%"></div>
                    </div>
                    <span>${player.riskScore || 0}%</span>
                </td>
                <td>${player.suspectedReason || '-'}</td>
                <td>${player.onlineTime || '0h 0m'}</td>
                <td>
                    <div class="action-buttons">
                        <button class="action-btn view" onclick="viewPlayer('${player.name}')">查看</button>
                        <button class="action-btn warn" onclick="warnPlayer('${player.name}')">警告</button>
                        <button class="action-btn ban" onclick="banPlayer('${player.name}')">封禁</button>
                    </div>
                </td>
            `;
            
            tbody.appendChild(tr);
        });
    } catch (e) {
        console.error('Failed to load players:', e);
    }
}

function viewPlayer(name) {
    alert('查看玩家: ' + name);
}

function warnPlayer(name) {
    if (confirm('确定要警告玩家 ' + name + ' 吗？')) {
        fetch('/api/broadcast', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({message: '管理员已向玩家 ' + name + ' 发出警告'})
        }).then(() => alert('警告已发送'));
    }
}

function banPlayer(name) {
    const duration = prompt('请输入封禁时长（如 1h, 1d, 7d, permanent）：', '1d');
    if (duration) {
        fetch('/api/ban', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({player: name, duration: duration, reason: 'Web面板封禁'})
        }).then(() => alert('封禁请求已发送'));
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
        if (!grid) return;
        
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
        
        if (!config.modules) {
            grid.innerHTML = '<div style="color: #666;">暂无配置数据</div>';
            return;
        }
        
        Object.entries(config.modules).forEach(([key, module]) => {
            const card = document.createElement('div');
            card.className = 'config-card';
            
            card.innerHTML = `
                <div class="config-header">
                    <span class="config-name">${modules[key] || key}</span>
                    <label class="config-toggle">
                        <input type="checkbox" ${module.enabled ? 'checked' : ''} onchange="updateModule('${key}', this.checked)">
                        <span class="slider"></span>
                    </label>
                </div>
                <div class="config-slider">
                    <input type="range" min="1" max="10" value="${module.sensitivity || 5}" oninput="updateSensitivity('${key}', this.value)">
                    <div class="slider-value">灵敏度: ${module.sensitivity || 5}</div>
                </div>
            `;
            
            grid.appendChild(card);
        });
    } catch (e) {
        console.error('Failed to load config:', e);
    }
}

function updateModule(module, enabled) {
    console.log(`Module ${module} enabled: ${enabled}`);
}

function updateSensitivity(module, value) {
    console.log(`Module ${module} sensitivity: ${value}`);
}

function setupMap() {
    mapCanvas = document.getElementById('mapCanvas');
    if (!mapCanvas) return;
    
    mapCtx = mapCanvas.getContext('2d');
    resizeMap();
    
    window.addEventListener('resize', resizeMap);
    drawMap();
    
    setInterval(drawMap, 5000);
}

function resizeMap() {
    if (!mapCanvas) return;
    
    const container = mapCanvas.parentElement;
    if (!container) return;
    
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
    
    fetch('/api/players')
        .then(res => res.json())
        .then(players => {
            if (!players || players.length === 0) {
                mapCtx.fillStyle = '#666';
                mapCtx.font = '16px Arial';
                mapCtx.textAlign = 'center';
                mapCtx.fillText('暂无在线玩家', width / 2, height / 2);
                return;
            }
            
            const centerX = width / 2;
            const centerY = height / 2;
            const radius = Math.min(width, height) * 0.3;
            
            players.forEach((player, index) => {
                const angle = (index / players.length) * Math.PI * 2 - Math.PI / 2;
                const px = centerX + Math.cos(angle) * radius;
                const py = centerY + Math.sin(angle) * radius;
                
                let color;
                if ((player.riskScore || 0) < 30) color = '#22c55e';
                else if ((player.riskScore || 0) < 60) color = '#eab308';
                else color = '#ef4444';
                
                mapCtx.beginPath();
                mapCtx.arc(px, py, 16, 0, Math.PI * 2);
                mapCtx.fillStyle = 'rgba(0, 229, 255, 0.2)';
                mapCtx.fill();
                
                mapCtx.beginPath();
                mapCtx.arc(px, py, 10, 0, Math.PI * 2);
                mapCtx.fillStyle = color;
                mapCtx.fill();
                
                mapCtx.beginPath();
                mapCtx.arc(px, py, 10, 0, Math.PI * 2);
                mapCtx.strokeStyle = '#ffffff';
                mapCtx.lineWidth = 2;
                mapCtx.stroke();
                
                mapCtx.fillStyle = '#ffffff';
                mapCtx.font = 'bold 12px Arial';
                mapCtx.textAlign = 'center';
                mapCtx.fillText(player.name || 'Unknown', px, py + 28);
            });
            
            mapCtx.fillStyle = '#888';
            mapCtx.font = '14px Arial';
            mapCtx.textAlign = 'center';
            mapCtx.fillText(`在线玩家: ${players.length}`, width / 2, height - 20);
        })
        .catch(() => {
            mapCtx.fillStyle = '#666';
            mapCtx.font = '16px Arial';
            mapCtx.textAlign = 'center';
            mapCtx.fillText('无法加载玩家数据', width / 2, height / 2);
        });
}

function setupCommandPalette() {
    const palette = document.getElementById('commandPalette');
    if (!palette) return;
    
    const input = palette.querySelector('.command-input');
    if (!input) return;
    
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
    
    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            executeCommand(input.value);
            closeCommandPalette();
        }
    });
}

function openCommandPalette() {
    const palette = document.getElementById('commandPalette');
    if (!palette) return;
    palette.classList.add('show');
    const input = palette.querySelector('.command-input');
    if (input) input.focus();
}

function closeCommandPalette() {
    const palette = document.getElementById('commandPalette');
    if (!palette) return;
    palette.classList.remove('show');
    const input = palette.querySelector('.command-input');
    if (input) input.value = '';
}

function executeCommand(cmd) {
    console.log('执行命令:', cmd);
    alert('命令已发送: ' + cmd);
}

function setupQuickActions() {
    const toggle = document.getElementById('quickActionsToggle');
    const menu = document.getElementById('quickActionsMenu');
    
    if (!toggle || !menu) return;
    
    toggle.addEventListener('click', () => {
        toggle.classList.toggle('open');
        menu.classList.toggle('show');
    });
    
    const actionButtons = menu.querySelectorAll('.quick-action-btn');
    actionButtons.forEach(btn => {
        btn.addEventListener('click', () => {
            const action = btn.textContent.trim();
            if (action.includes('快速封禁')) {
                const player = prompt('请输入要封禁的玩家名：');
                if (player) banPlayer(player);
            } else if (action.includes('全服扫描')) {
                alert('全服扫描已启动...');
            } else if (action.includes('发送广播')) {
                const msg = prompt('请输入广播内容：');
                if (msg) {
                    fetch('/api/broadcast', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({message: msg})
                    }).then(() => alert('广播已发送'));
                }
            }
        });
    });
}

function setupNotifications() {
    const btn = document.getElementById('notificationBtn');
    const dropdown = document.getElementById('notificationDropdown');
    
    if (!btn || !dropdown) return;
    
    btn.addEventListener('click', (e) => {
        e.stopPropagation();
        dropdown.classList.toggle('show');
    });
    
    document.addEventListener('click', () => {
        dropdown.classList.remove('show');
    });
}

function setupLogs() {
    const exportCsvBtn = document.getElementById('exportCsvBtn');
    const exportJsonBtn = document.getElementById('exportJsonBtn');
    
    if (exportCsvBtn) {
        exportCsvBtn.addEventListener('click', exportLogsAsCsv);
    }
    if (exportJsonBtn) {
        exportJsonBtn.addEventListener('click', exportLogsAsJson);
    }
    
    loadLogs();
}

function loadLogs() {
    const container = document.getElementById('logsContainer');
    if (!container) return;
    
    container.innerHTML = '<div style="color: #666; text-align: center; padding: 20px;">暂无日志记录</div>';
}

function exportLogsAsCsv() {
    const logs = getCurrentLogs();
    let csv = '时间,级别,消息\n';
    
    logs.forEach(log => {
        csv += `"${log.time}","${log.level}","${log.message}"\n`;
    });
    
    downloadFile(csv, 'audit_logs.csv', 'text/csv');
}

function exportLogsAsJson() {
    const logs = getCurrentLogs();
    const json = JSON.stringify(logs, null, 2);
    downloadFile(json, 'audit_logs.json', 'application/json');
}

function getCurrentLogs() {
    const logs = [];
    const logItems = document.querySelectorAll('.log-item');
    
    logItems.forEach(item => {
        const time = item.querySelector('.log-time')?.textContent || '';
        const level = item.querySelector('.log-level')?.textContent || '';
        const message = item.querySelector('.log-message')?.textContent || '';
        
        logs.push({
            time: time.replace(/\[|\]/g, ''),
            level: level.replace(/\[|\]/g, ''),
            message: message
        });
    });
    
    return logs.length > 0 ? logs : [
        { time: new Date().toLocaleString(), level: 'INFO', message: '系统启动' },
        { time: new Date().toLocaleString(), level: 'INFO', message: 'Web面板已就绪' }
    ];
}

function downloadFile(content, filename, mimeType) {
    const blob = new Blob([content], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    
    alert(`${filename} 已导出成功！`);
}

document.addEventListener('DOMContentLoaded', () => {
    init();
    setupLogs();
});
