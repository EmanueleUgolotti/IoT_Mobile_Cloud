import torch
import numpy as np
import torchvision.transforms as trans
import math
from scipy.fftpack import dct, idct


def apply_normalization(imgs, dataset=None):
    return imgs


def invert_normalization(imgs, dataset=None):
    return imgs


# get most likely predictions and probabilities
def get_preds(model, inputs, dataset_name=None, correct_class=None, batch_size=25):
    num_batches = int(math.ceil(inputs.size(0) / float(batch_size)))
    all_preds, all_probs = None, None

    for i in range(num_batches):
        upper = min((i + 1) * batch_size, inputs.size(0))
        input_tensor = inputs[(i * batch_size):upper]

        # call to the model
        output = model(input_tensor)

        # wrapper give probability
        if correct_class is None:
            prob, pred = output.max(1)
        else:
            prob = output[:, correct_class]
            pred = torch.ones(output.size(0)) * correct_class

        if i == 0:
            all_probs = prob
            all_preds = pred
        else:
            all_probs = torch.cat((all_probs, prob), 0)
            all_preds = torch.cat((all_preds, pred), 0)
    return all_preds, all_probs


# LOGICA PER ATTACCO DCT (Frequenza)
def diagonal_order(image_size, channels):
    x = torch.arange(0, image_size).cumsum(0)
    order = torch.zeros(image_size, image_size)
    for i in range(image_size):
        order[i, :(image_size - i)] = i + x[i:]
    for i in range(1, image_size):
        reverse = order[image_size - i - 1].index_select(0, torch.LongTensor([i for i in range(i - 1, -1, -1)]))
        order[i, (image_size - i):] = image_size * image_size - 1 - reverse
    if channels > 1:
        order_2d = order
        order = torch.zeros(channels, image_size, image_size)
        for i in range(channels):
            order[i, :, :] = 3 * order_2d + i
    return order.view(1, -1).squeeze().long().sort()[1]


def block_order(image_size, channels, initial_size=1, stride=1):
    order = torch.zeros(channels, image_size, image_size)
    total_elems = channels * initial_size * initial_size
    perm = torch.randperm(total_elems)
    order[:, :initial_size, :initial_size] = perm.view(channels, initial_size, initial_size).float()
    for i in range(initial_size, image_size, stride):
        num_elems = channels * (2 * stride * i + stride * stride)
        perm = torch.randperm(num_elems) + total_elems
        num_first = channels * stride * (stride + i)
        order[:, :(i + stride), i:(i + stride)] = perm[:num_first].view(channels, -1, stride).float()
        order[:, i:(i + stride), :i] = perm[num_first:].view(channels, stride, -1).float()
        total_elems += num_elems
    return order.view(1, -1).squeeze().long().sort()[1]


def block_idct(x, block_size=8, masked=False, ratio=0.5, linf_bound=0.0):
    z = torch.zeros(x.size())
    num_blocks = int(x.size(2) / block_size)
    mask = np.zeros((x.size(0), x.size(1), block_size, block_size))
    if type(ratio) != float:
        for i in range(x.size(0)):
            mask[i, :, :int(block_size * ratio[i]), :int(block_size * ratio[i])] = 1
    else:
        mask[:, :, :int(block_size * ratio), :int(block_size * ratio)] = 1
    for i in range(num_blocks):
        for j in range(num_blocks):
            submat = x[:, :, (i * block_size):((i + 1) * block_size), (j * block_size):((j + 1) * block_size)].numpy()
            if masked:
                submat = submat * mask
            z[:, :, (i * block_size):((i + 1) * block_size), (j * block_size):((j + 1) * block_size)] = torch.from_numpy(
                idct(idct(submat, axis=3, norm='ortho'), axis=2, norm='ortho'))
    if linf_bound > 0:
        return z.clamp(-linf_bound, linf_bound)
    else:
        return z
