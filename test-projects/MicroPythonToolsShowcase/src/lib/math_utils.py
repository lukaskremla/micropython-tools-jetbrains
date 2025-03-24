# Math Utility Library

import math

def average(values):
    if not values:
        return 0
    return sum(values) / len(values)

def distance(x1, y1, x2, y2):
    return math.sqrt((x2 - x1) ** 2 + (y2 - y1) ** 2)

def constrain(value, min_val, max_val):
    return max(min_val, min(max_val, value))

# Example usage:
# avg = average([1, 2, 3, 4, 5])
# dist = distance(0, 0, 3, 4)
