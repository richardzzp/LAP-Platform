import numpy as np
import torch
from lstm import LSTM
import sys

INPUT_DIM = 7
HIDDEN_DIM = 50
OUTPUT_DIM = 1
LSTM_LAYERS = 6

Max = {'63rd Street Weather Station': [34.1, 28.4, 1277. ], 'Oak Street Weather Station':[34.1, 27.5, 1175. ]}
Min = {'63rd Street Weather Station': [-19.5, -20.1, 0. ], 'Oak Street Weather Station':[-17.9, -18.7,  -6.]}

def predict(station, data):
    # station:string: 63rd Street Weather Station || Oak Street Weather Station
    # data: list with size 7*3
    # return: list with size 3
    model = LSTM(INPUT_DIM, HIDDEN_DIM, OUTPUT_DIM, LSTM_LAYERS)
    # model.load_state_dict(torch.load('model/'+station+'.pt',map_location=torch.device('cpu')), False)
    model.load_state_dict(torch.load('model/' + station + '.pt',map_location=torch.device('cpu')))
    model.eval()
    input = np.array(data).astype('float32')

    max = np.array(Max[station]).astype('float32')
    min = np.array(Min[station]).astype('float32')

    input = (input - min) / (max - min)
    inp = input.T.reshape(1, 3, 7)
    inp = torch.from_numpy(inp)
    output = model(inp)
    output = output.cpu().detach().numpy()
    output = output.T.reshape(3)
    output = (output * (max - min)) + min

    return output.tolist()

# data = [[21.299999237060547,16.0,847.0],
#         [20.899999618530273,16.200000762939453,918.0],
#         [22.200000762939453,16.5,553.0],
#         [20.5,15.899999618530273,79.0],
#         [20.200000762939453,15.800000190734863,60.0],
#         [19.899999618530273,15.5,41.0],
#         [19.799999237060547, 15.399999618530273, 12.0]]
# res = predict('63rd Street Weather Station', data)
# print(res[0],res[1],res[2])

if __name__ == '__main__':
    data=[]
    for i in range(1,20,3):
        data.append([sys.argv[i],sys.argv[i+1],sys.argv[i+2]])

    res=predict('63rd Street Weather Station',data)
    print(res[0],res[1],res[2])
