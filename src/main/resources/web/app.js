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
        } else if (page === 'cases') {
            loadCases();
        } else if (page === 'logs') {
            loadLogs();
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
            if (message.data.riskTrend && riskChart) {
                const MAX_DATA_POINTS = 8;
                const limitedData = message.data.riskTrend.slice(-MAX_DATA_POINTS);
                riskChart.data.datasets[0].data = limitedData;
                riskChart.update();
            }
            if (message.data.players) {
                refreshPlayersList(message.data.players);
            }
        } else if (message.type === 'caseUpdate') {
            loadCases();
        } else if (message.type === 'logUpdate') {
            if (document.getElementById('page-logs').classList.contains('active')) {
                loadLogs();
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
    const onlinePlayersEl = document.getElementById('onlinePlayers');
    const oldOnlineCount = parseInt(onlinePlayersEl?.textContent || '0');
    const newOnlineCount = data.onlinePlayers || 0;

    if (onlinePlayersEl) onlinePlayersEl.textContent = newOnlineCount;
    document.getElementById('suspectPlayers').textContent = data.suspectPlayers || 0;
    document.getElementById('todayIntercepts').textContent = data.todayIntercepts || 0;
    document.getElementById('captchaRate').textContent = Math.round((data.captchaSuccessRate || 0) * 100) + '%';
    document.getElementById('activeCases').textContent = data.activeCases || 0;

    const playerChangeEl = document.getElementById('playerChange');
    if (playerChangeEl) {
        const change = newOnlineCount - oldOnlineCount;
        if (change > 0) {
            playerChangeEl.className = 'metric-change positive';
            playerChangeEl.innerHTML = `+${change} <span class="arrow">↑</span>`;
        } else if (change < 0) {
            playerChangeEl.className = 'metric-change negative';
            playerChangeEl.innerHTML = `${change} <span class="arrow">↓</span>`;
        } else {
            playerChangeEl.className = 'metric-change';
            playerChangeEl.innerHTML = '';
        }
    }

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
    
    const MAX_DATA_POINTS = 8;
    const limitedData = data && data.length > 0 ? data.slice(-MAX_DATA_POINTS) : [];
    const labels = limitedData.length > 0 ? generateTimeLabels(limitedData.length) : ['暂无数据'];
    
    riskChart = new Chart(chartCtx, {
        type: 'line',
        data: {
            labels: labels,
            datasets: [{
                label: '风险评分',
                data: limitedData.length > 0 ? limitedData : [null],
                borderColor: '#00e5ff',
                backgroundColor: 'rgba(0, 229, 255, 0.1)',
                tension: 0.4,
                fill: true,
                pointRadius: limitedData.length > 0 ? 3 : 0,
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
                        maxRotation: 0,
                        maxTicksLimit: 8
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
        if (!response.ok) throw new Error('Failed to load events');
        const events = await response.json();

        const stream = document.getElementById('eventStream');
        if (!stream) return;

        if (!events || events.length === 0) {
            stream.innerHTML = '<div style="color: #666; text-align: center; padding: 20px;">暂无事件记录</div>';
            return;
        }

        stream.innerHTML = '';
        events.forEach(event => {
            addEventToStream(event);
        });
    } catch (e) {
        console.error('Failed to load events:', e);
        const stream = document.getElementById('eventStream');
        if (stream) {
            stream.innerHTML = '<div style="color: #ef4444; text-align: center; padding: 20px;">加载事件失败</div>';
        }
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

async function loadCases() {
    try {
        const response = await fetch('/api/cases');
        if (!response.ok) throw new Error('Failed to load cases');
        const cases = await response.json();

        const pendingContainer = document.getElementById('pending-cards');
        const reviewingContainer = document.getElementById('reviewing-cards');
        const closedContainer = document.getElementById('closed-cards');
        const pendingCount = document.getElementById('pending-count');
        const reviewingCount = document.getElementById('reviewing-count');
        const closedCount = document.getElementById('closed-count');

        if (pendingContainer) pendingContainer.innerHTML = '';
        if (reviewingContainer) reviewingContainer.innerHTML = '';
        if (closedContainer) closedContainer.innerHTML = '';

        let pending = 0, reviewing = 0, closed = 0;

        if (!cases || cases.length === 0) {
            if (pendingContainer) pendingContainer.innerHTML = '<div style="color: #666; text-align: center; padding: 20px;">暂无待审核案件</div>';
            if (reviewingContainer) reviewingContainer.innerHTML = '<div style="color: #666; text-align: center; padding: 20px;">暂无审理中案件</div>';
            if (closedContainer) closedContainer.innerHTML = '<div style="color: #666; text-align: center; padding: 20px;">暂无已结案案件</div>';
        } else {
            cases.forEach(caseItem => {
                const card = createCaseCard(caseItem);
                if (caseItem.status === 'pending') {
                    if (pendingContainer) pendingContainer.appendChild(card);
                    pending++;
                } else if (caseItem.status === 'reviewing') {
                    if (reviewingContainer) reviewingContainer.appendChild(card);
                    reviewing++;
                } else if (caseItem.status === 'closed') {
                    if (closedContainer) closedContainer.appendChild(card);
                    closed++;
                }
            });

            if (pending === 0 && pendingContainer) pendingContainer.innerHTML = '<div style="color: #666; text-align: center; padding: 20px;">暂无待审核案件</div>';
            if (reviewing === 0 && reviewingContainer) reviewingContainer.innerHTML = '<div style="color: #666; text-align: center; padding: 20px;">暂无审理中案件</div>';
            if (closed === 0 && closedContainer) closedContainer.innerHTML = '<div style="color: #666; text-align: center; padding: 20px;">暂无已结案案件</div>';
        }

        if (pendingCount) pendingCount.textContent = pending;
        if (reviewingCount) reviewingCount.textContent = reviewing;
        if (closedCount) closedCount.textContent = closed;
    } catch (e) {
        console.error('Failed to load cases:', e);
    }
}

function createCaseCard(caseItem) {
    const card = document.createElement('div');
    card.className = 'case-card' + (caseItem.status === 'closed' ? ' closed' : '');

    const riskClass = caseItem.riskLevel === 'high' ? 'high' : (caseItem.riskLevel === 'medium' ? 'medium' : 'low');
    const riskLabel = caseItem.riskLevel === 'high' ? '高' : (caseItem.riskLevel === 'medium' ? '中' : '低');

    let statusLabel = '';
    if (caseItem.status === 'closed') {
        statusLabel = caseItem.result === 'banned' ? '封禁' : '清除';
    }

    card.innerHTML = `
        <div class="case-header">
            <span class="case-player">${caseItem.player || 'Unknown'}</span>
            ${caseItem.status === 'closed'
                ? `<span class="case-result ${caseItem.result === 'banned' ? 'banned' : 'cleared'}">${statusLabel}</span>`
                : `<span class="case-risk ${riskClass}">${riskLabel}</span>`
            }
        </div>
        <div class="case-rule">${caseItem.module || 'Unknown'} 检测</div>
        <div class="case-time">${caseItem.time || ''}</div>
        ${caseItem.evidence ? `<div class="case-evidence">证据摘要：${caseItem.evidence}</div>` : ''}
    `;

    card.addEventListener('click', () => {
        if (caseItem.status !== 'closed') {
            const action = prompt('选择操作 (1: 开始审理, 2: 封禁玩家, 3: 清除嫌疑):', '1');
            handleCaseAction(caseItem, action);
        }
    });

    return card;
}

function handleCaseAction(caseItem, action) {
    if (action === '1') {
        fetch('/api/case/update', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({caseId: caseItem.id, status: 'reviewing'})
        }).then(() => {
            alert('案件已标记为审理中');
            loadCases();
        });
    } else if (action === '2') {
        if (confirm(`确定要封禁玩家 ${caseItem.player} 吗？`)) {
            fetch('/api/ban', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({player: caseItem.player, duration: 'permanent', reason: caseItem.module + ' 检测'})
            }).then(() => {
                fetch('/api/case/update', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({caseId: caseItem.id, status: 'closed', result: 'banned'})
                }).then(() => {
                    alert('玩家已封禁');
                    loadCases();
                });
            });
        }
    } else if (action === '3') {
        fetch('/api/case/update', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({caseId: caseItem.id, status: 'closed', result: 'cleared'})
        }).then(() => {
            alert('嫌疑已清除');
            loadCases();
        });
    }
}

function refreshPlayersList(players) {
    const tbody = document.getElementById('playersTableBody');
    if (!tbody || !document.getElementById('page-players').classList.contains('active')) return;

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

    container.innerHTML = '<div style="color: #666; text-align: center; padding: 20px;">正在加载日志...</div>';

    fetch('/api/logs')
        .then(res => {
            if (!res.ok) throw new Error('Failed to load logs');
            return res.json();
        })
        .then(logs => {
            if (!logs || logs.length === 0) {
                container.innerHTML = '<div style="color: #666; text-align: center; padding: 20px;">暂无日志记录</div>';
                return;
            }

            container.innerHTML = '';
            logs.forEach(log => {
                const logEl = document.createElement('div');
                logEl.className = 'log-item';

                const levelClass = log.level?.toLowerCase() || 'info';
                const levelColor = levelClass === 'danger' || levelClass === 'error' ? 'danger'
                    : levelClass === 'warning' ? 'warning'
                    : levelClass === 'success' ? 'success' : 'info';

                logEl.innerHTML = `
                    <span class="log-time">[${log.time || new Date().toLocaleString()}]</span>
                    <span class="log-level ${levelColor}">[${log.level || 'INFO'}]</span>
                    <span class="log-message">${log.message || ''}</span>
                `;

                container.appendChild(logEl);
            });
        })
        .catch(err => {
            console.error('Failed to load logs:', err);
            container.innerHTML = '<div style="color: #ef4444; text-align: center; padding: 20px;">加载日志失败</div>';
        });
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
        const timeEl = item.querySelector('.log-time');
        const levelEl = item.querySelector('.log-level');
        const messageEl = item.querySelector('.log-message');

        const time = timeEl?.textContent || '';
        const level = levelEl?.textContent || '';
        const message = messageEl?.textContent || '';

        if (time || level || message) {
            logs.push({
                time: time.replace(/\[|\]/g, '').trim(),
                level: level.replace(/\[|\]/g, '').trim(),
                message: message.trim()
            });
        }
    });

    return logs;
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
