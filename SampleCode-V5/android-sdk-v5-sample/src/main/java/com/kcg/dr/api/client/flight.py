MISSIONS = [
    {
        "name": "Patrol",
        "actions": [
            {'type': 'fly_by', 'dx': 0.5, 'velocity': 1.0},
            {'type': 'spin_by', 'degrees': 180.0},
            {'type': 'fly_by', 'dx': 0.5, 'velocity': 0.5},
            {'type': 'spin_by', 'degrees': 180.0},
            {'type': 'fly_by', 'dx': 0.5, 'velocity': 1.0},
            {'type': 'spin_by', 'degrees': 180.0},
            {'type': 'fly_by', 'dx': 0.5, 'velocity': 0.5},
            {'type': 'spin_by', 'degrees': 180.0},
        ]
    },
    {
        "name": "Scan",
        "actions": [
            {'type': 'gimbal_pitch', 'angle': -60.0},
            {'type': 'fly_circle', 'radius': 0.4, 'velocity': 1.0, 'clockwise': True},
            {'type': 'fly_by', 'dz': -0.5, 'velocity': 1.0},
            {'type': 'fly_circle', 'radius': 0.4, 'velocity': 1.0, 'clockwise': False},
            {'type': 'fly_by', 'dz': 0.5, 'velocity': 1.0},
        ]
    },
]
